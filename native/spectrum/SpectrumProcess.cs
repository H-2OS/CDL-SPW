// SPDX-License-Identifier: GPL-3.0-only
//
// Super Island for SPW —— 进程回环频谱助手 · 主程序
// ============================================================================
//
// ## 它是什么
//
// 一个**只读**的小工具：抓取"某个进程正在播放的音频"，算出频段电平，
// 按行写到 stdout。词岛（Java 侧）读这些行来画频谱。
//
// 它**不是**录音工具：不碰麦克风、不抓系统混音、不写任何音频文件。
// 数据流向是「进程 → 声卡」这条路径上的一个旁路副本，用完即弃。
//
// ## 为什么必须是独立进程（而不是在 JVM 里做）
//
// 进程回环的 API（`ActivateAudioInterfaceAsync("VAD\\Process_Loopback")`）
// 是 COM + 实时音频线程的活。放进 JVM 要走 JNI/JNA，等于自己写一层
// COM 绑定，出错时崩的是**整个播放器**。独立进程的故障域是隔离的：
// 助手挂了，词岛只是没有频谱，音乐照放。
//
// ## 命令行协议（**这是与 Kotlin 侧的唯一契约**）
//
// ```
//   argv[1]  = 目标进程 PID（十进制）。通常就是宿主 SPW 自己。
//   stdout   = 逐行文本：
//                "# …"          → 信息性输出，读方**应忽略**（只记日志）
//                "READY"        → 已开始抓取，之后才会有电平行
//                "0.12,0.34,…"  → 一组频段电平，0..1，个数由分析器决定
//                其它           → 错误说明，读方应作为"不可用原因"呈现
//   stderr   = 与 stdout 同流（读方用 redirectErrorStream 合并）
//   stdin    = 读到任意一行即**优雅退出**（读方 close() 时用它）
//   退出码   = 0 正常；1 出错（原因已写 stderr）
// ```
//
// 用 stdout 文本行而不是共享内存/命名管道，是**刻意的**：协议可读、
// 出问题时用户能直接手动跑一次看输出、也不需要在两侧同步结构体布局。
// 代价是每行约 100 字节 × 43 行/秒 ≈ 4 KB/s —— 可以忽略。
//
// ## 与上游 0.8.9 的 native/ 的关系（许可约束，如实说明）
//
// 用户已明确拍板「频谱 exe 自研编译」，故**没有拷贝上游的 C# 源码**。
// 参考了上游的**思路**（不是代码）：
//   · "用进程回环而非系统混音"这个方向 —— 这是需求决定的，不是实现选择；
//   · "stdout 逐行输出频段电平"这个协议**形状** —— 为了让 Java 侧读起来简单。
// 明确**没有**参考/沿用的部分：
//   · 音频格式策略：上游写死 44100/2ch/16bit；本实现**先问系统混音格式**，
//     失败才退回内置候选（见 ResolveFormats）—— 因为写死格式在
//     48 kHz 声卡或非 2 声道设备上会直接失败；
//   · 频段划分：上游是 4 段固定边界；本实现是 12 段对数等音程（见 SpectrumAnalyzer）；
//   · 电平定标：上游是"单一参考 + 指数肩部"；本实现是"dB 域 + 余量 + 硬动态范围"；
//   · FFT 归一化：上游用 Σw² 能量归一化；本实现用相干增益 W 归一化（推导见 SpectrumAnalyzer）；
//   · 声道处理：上游逐声道算功率再平均；本实现按偶/奇分组后取功率 max；
//   · 进程/线程生命周期、错误分类、日志格式均为本实现自有。

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace SuperIsland.Spectrum
{
    internal static class SpectrumProcess
    {
        /// <summary>等待音频激活完成的超时（毫秒）。见 <see cref="ActivateLoopback"/>。</summary>
        // ⚠️ 不能写成 `10_000` —— 数字分隔符是 C# 7 语法，而系统自带的
        //    csc.exe（.NET Framework 4.x）只到 C# 5。同理禁用的还有：
        //    字符串插值 `$"…"`、`nameof`、表达式体成员、`?.`。
        //    这些限制是**构建前提**，见 build.gradle.kts 的 buildSpectrum 任务。
        private const int ActivationTimeoutMs = 10000;

        /// <summary>
        /// 主循环里等待"有数据"的超时（毫秒）。
        ///
        /// 取 100 而不是 <c>Timeout.Infinite</c>：这个等待同时是**看门狗的心跳** ——
        /// 每 100 ms 醒一次去检查父进程是否还活着、stdin 有没有收到停止。
        /// 用无限等待的话，父进程异常退出后本进程会一直挂着（成为孤儿进程），
        /// 而它持有的回环流会让音频引擎一直认为"有人在抓"。
        /// </summary>
        private const int PumpTimeoutMs = 100;

        /// <summary>父进程已退出 / 收到停止信号。由 <see cref="Main"/> 与看门狗线程写。</summary>
        private static volatile bool stopping;

        [MTAThread]
        private static int Main(string[] args)
        {
            StreamWriter output = null;
            StreamWriter errors = null;
            try
            {
                // ⚠️ 必须用 Console.OpenStandardOutput() 显式拿流，而不是直接用 Console.Out：
                //    本程序编译成 **winexe**（见 build.gradle.kts 的 buildSpectrum 任务），
                //    目的是**不弹黑框**。winexe 进程没有控制台，Console.Out 会指向
                //    一个空设备；但父进程（Java）通过 CreateProcess 传进来的
                //    重定向管道句柄是有效的 —— 显式 OpenStandardOutput() 拿到的
                //    就是那个句柄。这是"无窗口 + 可通信"的标准做法。
                output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
                errors = new StreamWriter(Console.OpenStandardError()) { AutoFlush = true };
            }
            catch (Exception error)
            {
                // 连 stdout 都拿不到（例如被用无重定向的方式双击运行）→ 无处可写，
                // 只能靠退出码表达。此时 stderr 大概率也不可用，故包一层。
                TryWrite(errors, "# 无法打开标准输出：" + error.Message);
                return 1;
            }

            try
            {
                if (args.Length != 1)
                {
                    errors.WriteLine("用法：spw-spectrum.exe <目标进程 PID>");
                    return 1;
                }

                int pid;
                if (!int.TryParse(args[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out pid) || pid <= 0)
                {
                    errors.WriteLine("无效的进程 PID：" + args[0]);
                    return 1;
                }

                Process parent;
                try
                {
                    parent = Process.GetProcessById(pid);
                }
                catch (Exception error)
                {
                    errors.WriteLine("找不到进程 " + pid.ToString(CultureInfo.InvariantCulture) + "：" + error.Message);
                    return 1;
                }

                // 停止信号有两个来源，任一到达即退出：
                //   ① 父进程在 stdin 上写一行（正常关闭路径）；
                //   ② 父进程死了 → 管道 EOF → ReadLine 返回 null。
                // 后台线程（IsBackground = true）**不会**阻止进程退出 ——
                // 本项目在 Java 侧踩过"非守护线程拖住 JVM 退出"的坑，
                // 这里从一开始就用后台线程，避免同一个坑换语言再踩一次。
                var stopReader = new Thread(() =>
                {
                    try { Console.In.ReadLine(); }
                    catch (Exception) { /* 管道断了也是"该停了"，不区分 */ }
                    stopping = true;
                })
                { IsBackground = true, Name = "spectrum-stdin" };
                stopReader.Start();

                Capture(parent, output, errors);
                return 0;
            }
            catch (Exception error)
            {
                errors.WriteLine(Describe(error));
                return 1;
            }
            finally
            {
                try { output.Flush(); } catch (Exception) { }
            }
        }

        /// <summary>
        /// 激活进程回环、选一个能用的音频格式、然后跑抓取循环。
        ///
        /// ## 为什么格式要"逐个候选试"，而不是一次定死
        ///
        /// 进程回环的 `Initialize` 对格式有要求，而用户的声卡可能是
        /// 44.1k / 48k / 96k、2 声道或 6 声道、16 bit 或 32 bit float。
        /// 写死一种格式，在不符合的机器上会直接失败 —— 而用户看到的只是
        /// "频谱没反应"，没有任何线索。
        ///
        /// 故这里先问引擎要**系统混音格式**（`GetMixFormat`，几乎总能对上），
        /// 再准备两个内置候选兜底。每次尝试都用**全新的** IAudioClient ——
        /// `Initialize` 一旦被调用过，同一个 client 再调会返回
        /// `AUDCLNT_E_ALREADY_INITIALIZED`，复用它试第二个格式必然失败。
        /// </summary>
        private static void Capture(Process parent, TextWriter output, TextWriter errors)
        {
            IAudioClient client = null;
            ICaptureClient capture = null;
            SpectrumAnalyzer analyzer = null;
            try
            {
                var failures = new List<string>();
                ResolvedFormat chosen = null;

                foreach (FormatRequest request in FormatRequests())
                {
                    IAudioClient candidate = null;
                    try
                    {
                        candidate = ActivateLoopback(parent.Id);

                        ResolvedFormat resolved;
                        if (!request.TryResolve(candidate, out resolved))
                        {
                            failures.Add("· " + request.Name + "：无法取得该格式");
                            Marshal.ReleaseComObject(candidate);
                            continue;
                        }

                        WaveFormat native = resolved.Native;
                        candidate.Initialize(AudioFlags.ShareModeShared, AudioFlags.StreamFlags,
                                             0, 0, ref native, IntPtr.Zero);

                        client = candidate;
                        chosen = resolved;
                        break;
                    }
                    catch (Exception error)
                    {
                        failures.Add("· " + request.Name + "：" + Describe(error));
                        if (candidate != null)
                        {
                            try { Marshal.ReleaseComObject(candidate); } catch (Exception) { }
                        }
                    }
                }

                if (client == null)
                {
                    throw new InvalidOperationException(
                        "所有候选音频格式都被拒绝。可能原因：系统低于 Windows 10 20348、" +
                        "目标进程没有音频输出、或音频服务未运行。\n" + string.Join("\n", failures));
                }

                // 取得抓取接口。
                Guid captureIid = typeof(ICaptureClient).GUID;
                object service;
                client.GetService(ref captureIid, out service);
                capture = (ICaptureClient)service;

                analyzer = new SpectrumAnalyzer((int)chosen.SampleRate, chosen.Channels);
                output.WriteLine("# 格式：" + chosen.Description);
                output.WriteLine("# 频段中心频率(Hz)：" + analyzer.DescribeBands());
                // 把被跳过的候选也报出来。这是**现场诊断的关键线索**：
                // 用户报"频谱不亮"时，日志里"系统混音格式：GetMixFormat 失败"
                // 直接指向原因，而只报"用了内置候选 A"则什么也说明不了。
                foreach (string failure in failures) output.WriteLine("# 已跳过 " + failure);

                Run(client, capture, analyzer, chosen, parent, output, errors);
            }
            finally
            {
                if (analyzer != null) analyzer.Reset();
                if (capture != null) { try { Marshal.ReleaseComObject(capture); } catch (Exception) { } }
                if (client != null) { try { Marshal.ReleaseComObject(client); } catch (Exception) { } }
            }
        }

        /// <summary>抓取主循环。</summary>
        private static void Run(
            IAudioClient client, ICaptureClient capture, SpectrumAnalyzer analyzer,
            ResolvedFormat format, Process parent, TextWriter output, TextWriter errors)
        {
            var ready = new AutoResetEvent(false);
            var samples = new float[0];
            var bytes = new byte[0];
            bool parentAlive = true;

            try
            {
                client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle());
                client.Start();
                output.WriteLine("READY");

                while (!stopping)
                {
                    // 看门狗：父进程死了就必须退出。它死了以后 stdin 管道通常
                    // 也会 EOF（上面那个线程会置 stopping），但那**不保证** ——
                    // 若父进程是被强杀的，句柄可能还悬着。故这里显式再查一次。
                    if (parentAlive)
                    {
                        try { parentAlive = !parent.HasExited; }
                        catch (Exception) { parentAlive = false; }
                        if (!parentAlive) break;
                    }

                    ready.WaitOne(PumpTimeoutMs);

                    uint packetSize;
                    capture.GetNextPacketSize(out packetSize);
                    while (packetSize > 0 && !stopping)
                    {
                        IntPtr data;
                        uint frames;
                        uint flags;
                        ulong devicePosition;
                        ulong counterPosition;
                        capture.GetBuffer(out data, out frames, out flags, out devicePosition, out counterPosition);
                        try
                        {
                            if (frames > 0)
                            {
                                int sampleCount = checked((int)frames * format.Channels);
                                int byteCount = sampleCount * format.BytesPerSample;
                                if (bytes.Length < byteCount) bytes = new byte[byteCount];
                                if (samples.Length < sampleCount) samples = new float[sampleCount];

                                // 一次性把整包拷进托管数组，再在数组上解码 ——
                                // 比逐样本 Marshal.Read* 快得多，且每包只分配 0 次。
                                Marshal.Copy(data, bytes, 0, byteCount);
                                Decode(bytes, sampleCount, format, samples);

                                bool silent = (flags & AudioFlags.BufferFlagsSilent) != 0;
                                string line = analyzer.Push(samples, sampleCount, silent);
                                if (line != null)
                                {
                                    try
                                    {
                                        output.WriteLine(line);
                                    }
                                    catch (IOException)
                                    {
                                        // 读方（JVM）已经没了 → 管道断了。这是**正常**的
                                        // 结束路径之一，不是错误，故不写错误信息、退出码仍为 0。
                                        stopping = true;
                                    }
                                }
                            }
                        }
                        finally
                        {
                            // ⚠️ 必须无条件释放，且必须在 finally 里：
                            //    漏掉一次会让回环缓冲区**永久少一块**，表现为
                            //    频谱跑一会儿就彻底静止（缓冲区耗尽）。
                            //    中途抛异常（如解码失败）也必须释放。
                            capture.ReleaseBuffer(frames);
                        }

                        capture.GetNextPacketSize(out packetSize);
                    }
                }
            }
            finally
            {
                try { client.Stop(); } catch (Exception error) { TryWrite(errors, "# 停止音频客户端时出错：" + Describe(error)); }
                ready.Dispose();
            }
        }

        // ── 格式协商 ──────────────────────────────────────────────────────

        /// <summary>一个"格式请求"：要么去问引擎，要么用内置常量。</summary>
        private sealed class FormatRequest
        {
            internal string Name;
            internal bool UseMixFormat;
            internal ushort Tag;
            internal ushort Channels;
            internal uint SampleRate;
            internal ushort Bits;

            /// <summary>解析成可用的格式；失败返回 false（调用方会试下一个候选）。</summary>
            internal bool TryResolve(IAudioClient client, out ResolvedFormat resolved)
            {
                resolved = null;
                if (!UseMixFormat)
                {
                    resolved = ResolvedFormat.Create(
                        WaveFormat.Create(Tag, Channels, SampleRate, Bits),
                        Tag, Channels, SampleRate, Bits,
                        Name + "（" + SampleRate + " Hz / " + Channels + " ch / " + Bits + " bit）");
                    return true;
                }

                IntPtr pointer = IntPtr.Zero;
                try
                {
                    client.GetMixFormat(out pointer);
                    if (pointer == IntPtr.Zero) return false;
                    resolved = ReadMixFormat(pointer, Name);
                    return resolved != null;
                }
                catch (Exception)
                {
                    return false;
                }
                finally
                {
                    // GetMixFormat 分配的是 CoTaskMem，必须还回去。
                    if (pointer != IntPtr.Zero) Marshal.FreeCoTaskMem(pointer);
                }
            }
        }

        /// <summary>
        /// 候选格式列表，**按尝试顺序**。
        ///
        /// 顺序是有讲究的：混音格式排第一，因为它是引擎自己报的，
        /// 在绝大多数机器上一次就成；内置的两个只是"引擎没报/报了不支持的格式"
        /// 时的兜底，故放在后面（避免在一个本来能用的机器上先试错格式）。
        /// </summary>
        private static IEnumerable<FormatRequest> FormatRequests()
        {
            yield return new FormatRequest { Name = "系统混音格式", UseMixFormat = true };

            yield return new FormatRequest
            {
                Name = "内置候选 A",
                Tag = WaveFormat.TagFloat,
                Channels = 2,
                SampleRate = 48000,
                Bits = 32,
            };

            yield return new FormatRequest
            {
                Name = "内置候选 B",
                Tag = WaveFormat.TagPcm,
                Channels = 2,
                SampleRate = 44100,
                Bits = 16,
            };
        }

        /// <summary>
        /// 解析 <c>GetMixFormat</c> 返回的原始内存。
        ///
        /// ## 为什么手工按偏移读，而不是 <c>Marshal.PtrToStructure&lt;WaveFormatExtensible&gt;</c>
        ///
        /// 因为**我们不知道它是哪种结构**：<c>WAVEFORMATEX</c> 只有 18 字节，
        /// 而 <c>WAVEFORMATEXTENSIBLE</c> 有 40 字节，判据是 <c>wFormatTag</c>
        /// 是否为 0xFFFE。先按 18 字节的结构体读会读到越界（或读到未初始化内存），
        /// 且**不会报错** —— 只会得到一个看似合理但错误的格式。
        /// 手工按偏移读则只碰真正存在的字段。
        ///
        /// ## WAVE_FORMAT_EXTENSIBLE 的子格式
        ///
        /// 现代 Windows 的混音格式**几乎总是** EXTENSIBLE，此时
        /// <c>wFormatTag</c> = 0xFFFE 只是"去子格式 GUID 里看"的意思，
        /// 真正的类型在偏移 24 的 <c>SubFormat</c> GUID。
        /// 只看 <c>wFormatTag</c> 会把 32-bit float 当成 PCM 解，
        /// 结果是**满屏噪声**（把 IEEE754 位模式当整数读）。
        /// </summary>
        private static ResolvedFormat ReadMixFormat(IntPtr pointer, string name)
        {
            ushort tag = (ushort)Marshal.ReadInt16(pointer, 0);
            ushort channels = (ushort)Marshal.ReadInt16(pointer, 2);
            uint sampleRate = (uint)Marshal.ReadInt32(pointer, 4);
            ushort blockAlign = (ushort)Marshal.ReadInt16(pointer, 12);
            ushort bits = (ushort)Marshal.ReadInt16(pointer, 14);

            if (tag == WaveFormat.TagExtensible)
            {
                Guid subFormat = Marshal.PtrToStructure<Guid>(pointer + 24);
                if (subFormat == SubFormats.IeeeFloat) tag = WaveFormat.TagFloat;
                else if (subFormat == SubFormats.Pcm) tag = WaveFormat.TagPcm;
                else return null; // 其它子格式（如 Dolby）本助手不支持，交给下一个候选
            }

            if (channels == 0 || sampleRate == 0 || blockAlign == 0) return null;
            if (tag != WaveFormat.TagPcm && tag != WaveFormat.TagFloat) return null;
            if (tag == WaveFormat.TagFloat && bits != 32) return null;
            if (tag == WaveFormat.TagPcm && bits != 8 && bits != 16 && bits != 24 && bits != 32) return null;

            // 注意：这里**不重建** WaveFormat，而是把原始结构体的前 18 字节
            // 原样拷过来传给 Initialize。重建会丢掉 cbSize / 额外的通道掩码信息，
            // 对 EXTENSIBLE 格式可能被引擎判为不匹配。
            var native = (WaveFormat)Marshal.PtrToStructure(pointer, typeof(WaveFormat));
            return ResolvedFormat.Create(
                native, tag, channels, sampleRate, bits,
                name + "（" + sampleRate + " Hz / " + channels + " ch / " + bits + " bit " +
                (tag == WaveFormat.TagFloat ? "float" : "int") + "，块对齐 " + blockAlign + "）");
        }

        /// <summary>协商完成的格式：既含传给引擎的原生结构，也含解码需要的元信息。</summary>
        private sealed class ResolvedFormat
        {
            internal WaveFormat Native;
            internal ushort Tag;
            internal ushort Channels;
            internal uint SampleRate;
            internal ushort Bits;
            internal string Description;

            internal int BytesPerSample { get { return Bits / 8; } }

            internal static ResolvedFormat Create(
                WaveFormat native, ushort tag, ushort channels, uint sampleRate, ushort bits, string description)
            {
                return new ResolvedFormat
                {
                    Native = native,
                    Tag = tag,
                    Channels = channels,
                    SampleRate = sampleRate,
                    Bits = bits,
                    Description = description,
                };
            }
        }

        // ── 样本解码 ──────────────────────────────────────────────────────

        /// <summary>
        /// 把交错字节解码成 -1..1 的浮点样本。
        ///
        /// ⚠️ 本方法**不分配**（<paramref name="destination"/> 由调用方复用）——
        /// 它每秒被调用约 43 次、每次处理上千个样本，在里面分配会立刻变成
        /// GC 热点，而 GC 停顿在实时音频路径上表现为频谱的周期性卡顿。
        /// </summary>
        private static void Decode(byte[] source, int count, ResolvedFormat format, float[] destination)
        {
            switch (format.Tag)
            {
                case WaveFormat.TagFloat:
                    // 32-bit IEEE754，范围本来就是 -1..1（可能略微越界，无需 clamp）。
                    for (int i = 0; i < count; i++)
                    {
                        float value = BitConverter.ToSingle(source, i * 4);
                        // NaN / Inf 会污染整条 FFT 流水线（NaN 参与任何运算结果都是 NaN，
                        // 最终表现为"整排柱子恒定不动"）。故在入口就清掉。
                        destination[i] = IsFinite(value) ? value : 0f;
                    }
                    break;

                case WaveFormat.TagPcm:
                    switch (format.Bits)
                    {
                        case 16:
                            for (int i = 0; i < count; i++)
                            {
                                destination[i] = BitConverter.ToInt16(source, i * 2) / 32768f;
                            }
                            break;

                        case 32:
                            // 注意：32 bit 整数用 2147483648 而不是 2147483647 作除数 ——
                            // 前者让 -1.0 可达（Int32.MinValue 恰好映射到 -1.0），
                            // 后者会让最小负样本落在 -1.0000000005（越界）。
                            for (int i = 0; i < count; i++)
                            {
                                destination[i] = BitConverter.ToInt32(source, i * 4) / 2147483648f;
                            }
                            break;

                        case 24:
                            // 24 bit 打包存储（3 字节/样本），小端、需符号扩展。
                            for (int i = 0; i < count; i++)
                            {
                                int offset = i * 3;
                                int value = source[offset] | (source[offset + 1] << 8) | (source[offset + 2] << 16);
                                if ((value & 0x800000) != 0) value |= unchecked((int)0xFF000000);
                                destination[i] = value / 8388608f;
                            }
                            break;

                        default: // 8 bit：无符号，中点 128
                            for (int i = 0; i < count; i++)
                            {
                                destination[i] = (source[i] - 128) / 128f;
                            }
                            break;
                    }
                    break;

                default:
                    // 不可能到达：ReadMixFormat / FormatRequests 已经把 tag 收窄到两种。
                    throw new NotSupportedException("不支持的样本类型：" + format.Tag);
            }
        }

        /// <summary><c>float.IsFinite</c> 在 .NET Framework 4.x 上不存在，故自己写。</summary>
        private static bool IsFinite(float value)
        {
            return !float.IsNaN(value) && !float.IsInfinity(value);
        }

        // ── 激活 ──────────────────────────────────────────────────────────

        /// <summary>
        /// 激活"进程回环"音频客户端。
        ///
        /// ## 参数 blob 的三个 int32（顺序与含义由系统规定，不可换序）
        /// <code>
        ///   偏移 0 : ActivationType        = 1  (PROCESS_LOOPBACK)
        ///   偏移 4 : TargetProcessId       = 目标 PID
        ///   偏移 8 : ProcessLoopbackMode   = 0  (INCLUDE_TARGET_PROCESS_TREE)
        /// </code>
        ///
        /// `INCLUDE_TARGET_PROCESS_TREE` 是关键：SPW 的音频**不一定**由主进程输出 ——
        /// 音频可能来自它启动的子进程/解码线程。只抓单个进程会得到一个恒为静音的流，
        /// 表现为"频谱开关打开了但永远没有柱子"，且没有任何报错。
        /// 带上"进程树"语义才能覆盖这种情况。
        /// </summary>
        private static IAudioClient ActivateLoopback(int targetPid)
        {
            IntPtr blob = Marshal.AllocHGlobal(12);
            IActivationOperation operation = null;
            var handler = new ActivationHandler();
            try
            {
                Marshal.WriteInt32(blob, 0, 1);
                Marshal.WriteInt32(blob, 4, targetPid);
                Marshal.WriteInt32(blob, 8, 0);

                var parameters = new PropVariantBlob
                {
                    VariantType = PropVariantBlob.TypeBlob,
                    BlobSize = 12,
                    BlobData = blob,
                };

                Guid iid = typeof(IAudioClient).GUID;
                NativeMethods.Check(NativeMethods.ActivateAudioInterfaceAsync(
                    "VAD\\Process_Loopback", ref iid, ref parameters, handler, out operation));

                if (!handler.Completed.WaitOne(ActivationTimeoutMs))
                {
                    throw new TimeoutException(
                        "等待音频激活超时（" + ActivationTimeoutMs + " ms）。音频服务可能未运行。");
                }
                if (handler.Error != null) throw handler.Error;
                if (handler.Client == null) throw new InvalidOperationException("激活成功但没有返回音频客户端。");

                return (IAudioClient)handler.Client;
            }
            finally
            {
                if (operation != null) { try { Marshal.ReleaseComObject(operation); } catch (Exception) { } }
                Marshal.FreeHGlobal(blob);
            }
        }

        /// <summary>异步激活的完成回调。</summary>
        /// <remarks>
        /// ⚠️ 三个属性都是**必需**的，少一个都会以难懂的方式失败：
        /// · <c>[ComVisible(true)]</c> —— 不加则系统拿不到这个 COM 对象；
        /// · <c>ClassInterface(ClassInterfaceType.None)</c> —— 不加会自动生成类接口，
        ///   而自动接口的 GUID 每次编译都可能变；
        /// · 实现 <see cref="IAgileObject"/> —— 见该接口的说明（跨单元回调）。
        /// </remarks>
        [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
        internal sealed class ActivationHandler : IActivationHandler, IAgileObject
        {
            internal readonly ManualResetEvent Completed = new ManualResetEvent(false);
            internal object Client;
            internal Exception Error;

            public void ActivateCompleted(IActivationOperation operation)
            {
                try
                {
                    int hr;
                    operation.GetActivateResult(out hr, out Client);
                    NativeMethods.Check(hr);
                }
                catch (Exception error)
                {
                    Error = error;
                }
                finally
                {
                    // 必须在 finally 里 Set：否则激活失败时主线程会一直等到超时，
                    // 把一个"立刻可诊断的错误"拖成 10 秒的卡顿。
                    Completed.Set();
                }
            }
        }

        // ── 杂项 ──────────────────────────────────────────────────────────

        /// <summary>把异常变成一行可读文本，并带上 HRESULT（便于对照 MSDN 排查）。</summary>
        private static string Describe(Exception error)
        {
            if (error is COMException)
            {
                return error.Message + " [HRESULT " + NativeMethods.Describe(((COMException)error).ErrorCode) + "]";
            }
            return error.GetType().Name + "：" + error.Message;
        }

        /// <summary>尽力写一行日志；写不进去就算了（用于错误处理路径自身）。</summary>
        private static void TryWrite(TextWriter writer, string line)
        {
            if (writer == null) return;
            try { writer.WriteLine(line); } catch (Exception) { }
        }
    }
}

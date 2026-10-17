// SPDX-License-Identifier: GPL-3.0-only
//
// Super Island for SPW —— 进程回环频谱助手 · Windows API 互操作层
// ============================================================================
//
// ## 这个文件里为什么全是"看起来像抄来的"声明
//
// 本文件只包含 **Windows 公开 API 的声明**：COM 接口的 IID、虚表方法顺序、
// 结构体字段布局、P/Invoke 签名。这些东西**不是实现，是接口契约** ——
// 它们的值由操作系统单方面规定，任何调用方都必须写成完全一样，
// 否则 COM 调用会在错误的槽位上跳到错误的函数。也就是说：
// 「这里的每个 GUID / 字段偏移，换谁来写都只能是这个值」。
//
// 真正的**实现**（怎么抓音频、怎么做 FFT、怎么定标）在
// `SpectrumProcess.cs` 与 `SpectrumAnalyzer.cs`，那两个文件是独立设计的。
//
// ## 与上游 0.8.9 的 native/ 的关系（许可约束，如实说明）
//
// 用户已明确拍板「频谱 exe 自研编译」，故**没有拷贝上游的 C# 源码**。
// 本文件与上游 `native/AudioInterop.cs` 的**交集仅限 API 声明**（上述契约部分）。
// 结构体命名、接口拆分方式、注释与错误处理策略均为本项目自有。
//
// ## 用到的 API 一句话说明
//
// `ActivateAudioInterfaceAsync("VAD\\Process_Loopback", …)` 是 Windows 10 20348+
// 提供的能力：**只抓指定进程（及其子树）的音频**，不碰麦克风、不抓系统混音、
// 不落盘。它不是"录音"，只是把本进程已经送去声卡的数据再复制一份出来做分析。

using System;
using System.Runtime.InteropServices;

namespace SuperIsland.Spectrum
{
    // ── 音频客户端共享模式 / 流标志 ────────────────────────────────────────
    internal static class AudioFlags
    {
        /// <summary>共享模式。进程回环只支持共享模式。</summary>
        internal const int ShareModeShared = 0;

        /// <summary>回环：抓"正在播放"的数据而不是"要送去播放"的数据。</summary>
        internal const uint Loopback = 0x00020000;

        /// <summary>事件驱动：缓冲区有数据时由系统 SetEvent，避免忙等轮询。</summary>
        internal const uint EventCallback = 0x00040000;

        /// <summary>
        /// 允许音频引擎做采样率转换。
        ///
        /// ⚠️ 这个标志是"格式协商"的关键：进程回环**只接受某些格式**，
        /// 而用户机器的混音格式可能是 48 kHz / 32-bit float / 6 声道。
        /// 带上它，我们就能用一套固定格式（见 <see cref="SpectrumFormats.Candidates"/>）
        /// 去初始化，由引擎负责转换 —— 于是助手进程**不需要**知道用户的声卡细节。
        /// </summary>
        internal const uint AutoConvertPcm = 0x80000000;

        /// <summary>
        /// 转换质量取系统默认（由控制面板的"音频增强"设置决定）。
        /// MSDN 规定 <see cref="AutoConvertPcm"/> 要配上它才有确定语义，故两个一起给。
        /// </summary>
        internal const uint SrcDefaultQuality = 0x08000000;

        /// <summary>本助手实际使用的流标志组合。见上面三个常量的说明。</summary>
        internal const uint StreamFlags = Loopback | EventCallback | AutoConvertPcm | SrcDefaultQuality;

        /// <summary>缓冲区没有任何数据可读（<c>GetBuffer</c> 之外的探测用）。</summary>
        internal const uint BufferFlagsSilent = 0x2;
    }

    // ── 激活参数（PROPVARIANT + AUDIOCLIENT_ACTIVATION_PARAMS）──────────────

    /// <summary>
    /// <c>ActivateAudioInterfaceAsync</c> 的第三个参数是 <c>PROPVARIANT*</c>。
    ///
    /// 这里用 **Explicit 布局**精确还原它的内存形态，而不是去引 <c>System.Runtime.InteropServices.ComTypes</c>
    /// 里的 <c>PropVariant</c>（那个在 .NET Framework 里是 internal，用不了）。
    ///
    /// x64 下 <c>PROPVARIANT</c> 恒为 24 字节：
    /// <code>
    ///   偏移 0  : vt        (VARTYPE = ushort，这里取 VT_BLOB = 65)
    ///   偏移 2  : wReserved1/2/3 (3 × ushort，恒 0)
    ///   偏移 8  : union { BLOB { ULONG cbSize; BYTE* pBlob; } }
    ///             其中 cbSize 在偏移 8、pBlob 在偏移 16（x64 指针 8 字节对齐）
    /// </code>
    ///
    /// ⚠️ 这三个偏移是**硬约束**：写错了 <c>ActivateAudioInterfaceAsync</c> 会返回
    /// <c>E_INVALIDARG</c> 或直接崩在系统侧，而且不会有任何可读的提示。
    /// </summary>
    [StructLayout(LayoutKind.Explicit, Size = 24)]
    internal struct PropVariantBlob
    {
        /// <summary>VARTYPE。BLOB 取 65（<c>VT_BLOB</c>）。</summary>
        [FieldOffset(0)] internal ushort VariantType;

        /// <summary>BLOB 的字节数。</summary>
        [FieldOffset(8)] internal uint BlobSize;

        /// <summary>指向 BLOB 数据（即 <c>AUDIOCLIENT_ACTIVATION_PARAMS</c>）的指针。</summary>
        [FieldOffset(16)] internal IntPtr BlobData;

        /// <summary>VT_BLOB —— 见 <see cref="VariantType"/>。</summary>
        internal const ushort TypeBlob = 65;
    }

    // ── COM 接口声明 ──────────────────────────────────────────────────────
    //
    // ⚠️ 下面每个接口的 **方法顺序 = 虚表顺序**，必须与 Windows SDK 头文件一字不差。
    //    顺序错了不会编译报错，而是在运行时跳到另一个函数指针上（典型症状是
    //    "访问冲突"或"HRESULT 完全对不上"，极难归因）。

    /// <summary>异步激活的结果句柄（<c>IActivateAudioInterfaceAsyncOperation</c>）。</summary>
    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IActivationOperation
    {
        /// <summary>取激活结果。<paramref name="hr"/> 非 0 时 <paramref name="instance"/> 无意义。</summary>
        void GetActivateResult(out int hr, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    /// <summary>激活完成回调。系统在**另一个线程**上调用它，故实现方必须可跨单元调度。</summary>
    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IActivationHandler
    {
        void ActivateCompleted(IActivationOperation operation);
    }

    /// <summary>
    /// 标记接口：声明"我不在乎被跨单元调用"。
    ///
    /// 没有它，回调对象会被钉在 MTA 上，而系统从别的单元回调时会得到
    /// <c>RPC_E_WRONG_THREAD</c>。空接口不代表可以省 —— 它是有语义的。
    /// </summary>
    [ComImport, Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAgileObject { }

    /// <summary>音频客户端。仅声明本助手用得到的那几个方法（顺序仍需完整）。</summary>
    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        void Initialize(int shareMode, uint streamFlags, long bufferDuration, long periodicity,
                        ref WaveFormat format, IntPtr sessionGuid);
        void GetBufferSize(out uint frames);
        void GetStreamLatency(out long latency);
        void GetCurrentPadding(out uint frames);
        [PreserveSig] int IsFormatSupported(int shareMode, ref WaveFormat format, out IntPtr closestMatch);
        void GetMixFormat(out IntPtr format);
        void GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        void Start();
        void Stop();
        void Reset();
        void SetEventHandle(IntPtr handle);
        void GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    /// <summary>抓取客户端。回环流通过 <c>GetService</c> 取得。</summary>
    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface ICaptureClient
    {
        void GetBuffer(out IntPtr data, out uint frames, out uint flags,
                       out ulong devicePosition, out ulong counterPosition);
        void ReleaseBuffer(uint frames);
        void GetNextPacketSize(out uint frames);
    }

    // ── 结构体 ────────────────────────────────────────────────────────────

    /// <summary>
    /// <c>WAVEFORMATEX</c>（18 字节）。
    ///
    /// <c>Pack = 2</c> 是为了精确匹配 C 的字段对齐：C 结构体里
    /// <c>wFormatTag</c>/<c>nChannels</c> 是 2 字节，紧跟的 <c>nSamplesPerSec</c>
    /// 是 4 字节且在偏移 4 —— 用默认 Pack 恰好也能得到同样结果，
    /// 但显式写 2 能保证换平台/换编译器时不会漂。
    /// </summary>
    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    internal struct WaveFormat
    {
        internal ushort FormatTag;
        internal ushort Channels;
        internal uint SamplesPerSecond;
        internal uint AverageBytesPerSecond;
        internal ushort BlockAlign;
        internal ushort BitsPerSample;
        internal ushort ExtraSize;

        /// <summary>WAVE_FORMAT_PCM。</summary>
        internal const ushort TagPcm = 1;

        /// <summary>WAVE_FORMAT_IEEE_FLOAT。</summary>
        internal const ushort TagFloat = 3;

        /// <summary>
        /// WAVE_FORMAT_EXTENSIBLE —— 真正的格式由 <c>WAVEFORMATEXTENSIBLE.SubFormat</c>
        /// 这个 GUID 决定。现代 Windows 的混音格式**几乎总是**这个。
        /// </summary>
        internal const ushort TagExtensible = 0xFFFE;

        /// <summary>手工构造一个 PCM/float 格式（用于候选格式，见 <see cref="SpectrumFormats"/>）。</summary>
        internal static WaveFormat Create(ushort tag, ushort channels, uint sampleRate, ushort bits)
        {
            ushort blockAlign = (ushort)(channels * bits / 8);
            return new WaveFormat
            {
                FormatTag = tag,
                Channels = channels,
                SamplesPerSecond = sampleRate,
                AverageBytesPerSecond = sampleRate * blockAlign,
                BlockAlign = blockAlign,
                BitsPerSample = bits,
                ExtraSize = 0,
            };
        }
    }

    /// <summary>KSDATAFORMAT 子格式 GUID（用于解析 WAVE_FORMAT_EXTENSIBLE）。</summary>
    internal static class SubFormats
    {
        internal static readonly Guid Pcm = new Guid("00000001-0000-0010-8000-00AA00389B71");
        internal static readonly Guid IeeeFloat = new Guid("00000003-0000-0010-8000-00AA00389B71");
    }

    // ── P/Invoke ──────────────────────────────────────────────────────────

    internal static class NativeMethods
    {
        /// <summary>
        /// 异步激活一个音频接口。<c>"VAD\\Process_Loopback"</c> 是进程回环的固定设备路径。
        ///
        /// 为什么是"异步"：这个激活要等音频引擎起来，可能耗时几百毫秒，
        /// 同步版本会阻塞调用线程且没有超时控制。异步 + 等待事件是官方推荐用法。
        /// </summary>
        [DllImport("Mmdevapi.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
        internal static extern int ActivateAudioInterfaceAsync(
            string deviceInterfacePath,
            ref Guid interfaceId,
            ref PropVariantBlob activationParams,
            IActivationHandler handler,
            out IActivationOperation operation);

        /// <summary>把 HRESULT 转成异常。<c>S_OK</c> 与正数（<c>S_FALSE</c> 之类）都算成功。</summary>
        internal static void Check(int hr)
        {
            if (hr < 0) Marshal.ThrowExceptionForHR(hr);
        }

        /// <summary>把失败码格式化成 <c>0xXXXXXXXX</c>，便于用户/日志对照 MSDN。</summary>
        internal static string Describe(int hr)
        {
            // 不用 `?.`（C# 6 语法，系统自带的 csc.exe 编不过，见 buildSpectrum 任务）。
            Exception detail = Marshal.GetExceptionForHR(hr);
            string message = detail == null ? "系统未提供说明" : detail.Message;
            return "0x" + hr.ToString("X8") + " (" + message + ")";
        }
    }
}

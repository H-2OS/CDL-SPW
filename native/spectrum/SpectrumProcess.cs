// SPDX-License-Identifier: GPL-3.0-only
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

        private const int ActivationTimeoutMs = 10000;

        private const int PumpTimeoutMs = 100;

        private static volatile bool stopping;

        [MTAThread]
        private static int Main(string[] args)
        {
            StreamWriter output = null;
            StreamWriter errors = null;
            try
            {

                output = new StreamWriter(Console.OpenStandardOutput()) { AutoFlush = true };
                errors = new StreamWriter(Console.OpenStandardError()) { AutoFlush = true };
            }
            catch (Exception error)
            {

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

                var stopReader = new Thread(() =>
                {
                    try { Console.In.ReadLine(); }
                    catch (Exception) {  }
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

                Guid captureIid = typeof(ICaptureClient).GUID;
                object service;
                client.GetService(ref captureIid, out service);
                capture = (ICaptureClient)service;

                analyzer = new SpectrumAnalyzer((int)chosen.SampleRate, chosen.Channels);
                output.WriteLine("# 格式：" + chosen.Description);
                output.WriteLine("# 频段中心频率(Hz)：" + analyzer.DescribeBands());

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

                                        stopping = true;
                                    }
                                }
                            }
                        }
                        finally
                        {

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

        private sealed class FormatRequest
        {
            internal string Name;
            internal bool UseMixFormat;
            internal ushort Tag;
            internal ushort Channels;
            internal uint SampleRate;
            internal ushort Bits;

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

                    if (pointer != IntPtr.Zero) Marshal.FreeCoTaskMem(pointer);
                }
            }
        }

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
                else return null;
            }

            if (channels == 0 || sampleRate == 0 || blockAlign == 0) return null;
            if (tag != WaveFormat.TagPcm && tag != WaveFormat.TagFloat) return null;
            if (tag == WaveFormat.TagFloat && bits != 32) return null;
            if (tag == WaveFormat.TagPcm && bits != 8 && bits != 16 && bits != 24 && bits != 32) return null;

            var native = (WaveFormat)Marshal.PtrToStructure(pointer, typeof(WaveFormat));
            return ResolvedFormat.Create(
                native, tag, channels, sampleRate, bits,
                name + "（" + sampleRate + " Hz / " + channels + " ch / " + bits + " bit " +
                (tag == WaveFormat.TagFloat ? "float" : "int") + "，块对齐 " + blockAlign + "）");
        }

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

        private static void Decode(byte[] source, int count, ResolvedFormat format, float[] destination)
        {
            switch (format.Tag)
            {
                case WaveFormat.TagFloat:

                    for (int i = 0; i < count; i++)
                    {
                        float value = BitConverter.ToSingle(source, i * 4);

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

                            for (int i = 0; i < count; i++)
                            {
                                destination[i] = BitConverter.ToInt32(source, i * 4) / 2147483648f;
                            }
                            break;

                        case 24:

                            for (int i = 0; i < count; i++)
                            {
                                int offset = i * 3;
                                int value = source[offset] | (source[offset + 1] << 8) | (source[offset + 2] << 16);
                                if ((value & 0x800000) != 0) value |= unchecked((int)0xFF000000);
                                destination[i] = value / 8388608f;
                            }
                            break;

                        default:
                            for (int i = 0; i < count; i++)
                            {
                                destination[i] = (source[i] - 128) / 128f;
                            }
                            break;
                    }
                    break;

                default:

                    throw new NotSupportedException("不支持的样本类型：" + format.Tag);
            }
        }

        private static bool IsFinite(float value)
        {
            return !float.IsNaN(value) && !float.IsInfinity(value);
        }

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

                    Completed.Set();
                }
            }
        }

        private static string Describe(Exception error)
        {
            if (error is COMException)
            {
                return error.Message + " [HRESULT " + NativeMethods.Describe(((COMException)error).ErrorCode) + "]";
            }
            return error.GetType().Name + "：" + error.Message;
        }

        private static void TryWrite(TextWriter writer, string line)
        {
            if (writer == null) return;
            try { writer.WriteLine(line); } catch (Exception) { }
        }
    }
}

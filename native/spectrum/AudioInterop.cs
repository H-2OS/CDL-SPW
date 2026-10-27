// SPDX-License-Identifier: GPL-3.0-only
using System;
using System.Runtime.InteropServices;

namespace SuperIsland.Spectrum
{

    internal static class AudioFlags
    {

        internal const int ShareModeShared = 0;

        internal const uint Loopback = 0x00020000;

        internal const uint EventCallback = 0x00040000;

        internal const uint AutoConvertPcm = 0x80000000;

        internal const uint SrcDefaultQuality = 0x08000000;

        internal const uint StreamFlags = Loopback | EventCallback | AutoConvertPcm | SrcDefaultQuality;

        internal const uint BufferFlagsSilent = 0x2;
    }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    internal struct PropVariantBlob
    {

        [FieldOffset(0)] internal ushort VariantType;

        [FieldOffset(8)] internal uint BlobSize;

        [FieldOffset(16)] internal IntPtr BlobData;

        internal const ushort TypeBlob = 65;
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IActivationOperation
    {

        void GetActivateResult(out int hr, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IActivationHandler
    {
        void ActivateCompleted(IActivationOperation operation);
    }

    [ComImport, Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAgileObject { }

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

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface ICaptureClient
    {
        void GetBuffer(out IntPtr data, out uint frames, out uint flags,
                       out ulong devicePosition, out ulong counterPosition);
        void ReleaseBuffer(uint frames);
        void GetNextPacketSize(out uint frames);
    }

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

        internal const ushort TagPcm = 1;

        internal const ushort TagFloat = 3;

        internal const ushort TagExtensible = 0xFFFE;

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

    internal static class SubFormats
    {
        internal static readonly Guid Pcm = new Guid("00000001-0000-0010-8000-00AA00389B71");
        internal static readonly Guid IeeeFloat = new Guid("00000003-0000-0010-8000-00AA00389B71");
    }

    internal static class NativeMethods
    {

        [DllImport("Mmdevapi.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
        internal static extern int ActivateAudioInterfaceAsync(
            string deviceInterfacePath,
            ref Guid interfaceId,
            ref PropVariantBlob activationParams,
            IActivationHandler handler,
            out IActivationOperation operation);

        internal static void Check(int hr)
        {
            if (hr < 0) Marshal.ThrowExceptionForHR(hr);
        }

        internal static string Describe(int hr)
        {

            Exception detail = Marshal.GetExceptionForHR(hr);
            string message = detail == null ? "系统未提供说明" : detail.Message;
            return "0x" + hr.ToString("X8") + " (" + message + ")";
        }
    }
}

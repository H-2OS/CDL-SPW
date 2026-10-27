// SPDX-License-Identifier: GPL-3.0-only
using System;
using System.Globalization;
using System.Text;

namespace SuperIsland.Spectrum
{

    internal sealed class SpectrumAnalyzer
    {

        private const int FftSize = 2048;

        private const int HopSize = FftSize / 2;

        internal const int BandCount = 24;

        private const double MinFrequencyHz = 45.0;

        private const double MaxFrequencyHz = 16000.0;

        private const double SilenceDb = -72.0;

        private const double NoiseFloorRms = 1e-5;

        private const double HeadroomDb = 6.0;

        private const double DynamicRangeDb = 42.0;

        private const double ReferenceAttackSeconds = 0.20;

        private const double ReferenceReleaseSeconds = 3.0;

        private const double ReferenceForgetSeconds = 0.4;

        private readonly double[] windowA = new double[FftSize];
        private readonly double[] windowB = new double[FftSize];

        private int filled;

        private readonly double[] hann = new double[FftSize];

        private readonly double windowGain;

        private readonly double[] real = new double[FftSize];
        private readonly double[] imag = new double[FftSize];

        private readonly double[] rmsA = new double[BandCount];
        private readonly double[] rmsB = new double[BandCount];

        private readonly double[] levelsScratch = new double[BandCount];

        private readonly int[] bandStart = new int[BandCount];
        private readonly int[] bandEnd = new int[BandCount];

        private readonly double[] bandCenterHz = new double[BandCount];

        private double referenceDb = double.NegativeInfinity;

        private double silentSeconds;

        private readonly double sampleRate;

        private readonly int channels;

        internal double SampleRate { get { return sampleRate; } }

        internal int Channels { get { return channels; } }

        internal SpectrumAnalyzer(int sampleRate, int channels)
        {
            this.sampleRate = sampleRate;
            this.channels = channels < 1 ? 1 : channels;

            double sum = 0.0;
            for (int i = 0; i < FftSize; i++)
            {
                double w = 0.5 - 0.5 * Math.Cos(2.0 * Math.PI * i / (FftSize - 1));
                hann[i] = w;
                sum += w;
            }
            windowGain = sum;

            BuildBands();
        }

        private void BuildBands()
        {
            double ratio = Math.Pow(MaxFrequencyHz / MinFrequencyHz, 1.0 / BandCount);
            for (int b = 0; b < BandCount; b++)
            {
                double lowHz = MinFrequencyHz * Math.Pow(ratio, b);
                double highHz = lowHz * ratio;

                int start = Math.Max(1, (int)Math.Round(lowHz * FftSize / SampleRate));

                int end = Math.Min(FftSize / 2, (int)Math.Round(highHz * FftSize / SampleRate));

                bandStart[b] = start;
                bandEnd[b] = Math.Max(start + 1, end);
                bandCenterHz[b] = Math.Sqrt(lowHz * highHz);
            }

            for (int b = 1; b < BandCount; b++)
            {
                if (bandStart[b] < bandEnd[b - 1]) bandStart[b] = bandEnd[b - 1];
            }
        }

        internal string Push(float[] interleaved, int count, bool isSilent)
        {
            string last = null;
            int frames = count / Channels;
            for (int f = 0; f < frames; f++)
            {
                int baseIndex = f * Channels;

                double a = 0.0, b = 0.0;
                int aCount = 0, bCount = 0;
                for (int c = 0; c < Channels; c++)
                {
                    double v = isSilent ? 0.0 : interleaved[baseIndex + c];
                    if ((c & 1) == 0) { a += v; aCount++; } else { b += v; bCount++; }
                }
                a = aCount > 0 ? a / aCount : 0.0;
                b = bCount > 0 ? b / bCount : a;

                windowA[filled] = a;
                windowB[filled] = b;
                filled++;
                if (filled < FftSize) continue;

                last = Analyze();

                Array.Copy(windowA, HopSize, windowA, 0, FftSize - HopSize);
                Array.Copy(windowB, HopSize, windowB, 0, FftSize - HopSize);
                filled = FftSize - HopSize;
            }
            return last;
        }

        private string Analyze()
        {
            double dt = (double)HopSize / SampleRate;

            BandRms(windowA, rmsA);
            BandRms(windowB, rmsB);

            double peakDb = double.NegativeInfinity;
            for (int i = 0; i < BandCount; i++)
            {
                double value = rmsA[i] > rmsB[i] ? rmsA[i] : rmsB[i];
                if (value < NoiseFloorRms) value = 0.0;
                levelsScratch[i] = value;
                if (value > 0.0)
                {
                    double db = 20.0 * Math.Log10(value);
                    if (db > peakDb) peakDb = db;
                }
            }

            if (peakDb < SilenceDb)
            {
                silentSeconds += dt;
                if (silentSeconds >= ReferenceForgetSeconds)
                {

                    referenceDb = double.NegativeInfinity;
                }
                return Format(new double[BandCount]);
            }
            silentSeconds = 0.0;

            if (double.IsNegativeInfinity(referenceDb))
            {

                referenceDb = peakDb;
            }
            else
            {
                double tau = peakDb > referenceDb ? ReferenceAttackSeconds : ReferenceReleaseSeconds;
                referenceDb += (peakDb - referenceDb) * (1.0 - Math.Exp(-dt / tau));
            }

            double floorDb = referenceDb + HeadroomDb - DynamicRangeDb;
            double[] levels = new double[BandCount];
            for (int i = 0; i < BandCount; i++)
            {
                double rms = levelsScratch[i];
                if (rms <= 0.0) { levels[i] = 0.0; continue; }
                double db = 20.0 * Math.Log10(rms);
                double normalized = (db - floorDb) / DynamicRangeDb;
                levels[i] = normalized < 0.0 ? 0.0 : (normalized > 1.0 ? 1.0 : normalized);
            }
            return Format(levels);
        }

        private void BandRms(double[] source, double[] destination)
        {
            for (int i = 0; i < FftSize; i++)
            {
                real[i] = source[i] * hann[i];
                imag[i] = 0.0;
            }

            ForwardFft();

            for (int b = 0; b < BandCount; b++)
            {
                double power = 0.0;
                for (int k = bandStart[b]; k < bandEnd[b]; k++)
                {
                    power += real[k] * real[k] + imag[k] * imag[k];
                }
                destination[b] = Math.Sqrt(2.0 * power) / windowGain;
            }
        }

        private void ForwardFft()
        {

            for (int i = 1, j = 0; i < FftSize; i++)
            {
                int bit = FftSize >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j)
                {
                    double tr = real[i]; real[i] = real[j]; real[j] = tr;
                    double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
                }
            }

            for (int length = 2; length <= FftSize; length <<= 1)
            {
                int half = length >> 1;
                double angle = -2.0 * Math.PI / length;
                for (int start = 0; start < FftSize; start += length)
                {
                    for (int k = 0; k < half; k++)
                    {
                        double wr = Math.Cos(angle * k);
                        double wi = Math.Sin(angle * k);
                        int even = start + k;
                        int odd = even + half;
                        double tr = real[odd] * wr - imag[odd] * wi;
                        double ti = real[odd] * wi + imag[odd] * wr;
                        real[odd] = real[even] - tr;
                        imag[odd] = imag[even] - ti;
                        real[even] += tr;
                        imag[even] += ti;
                    }
                }
            }
        }

        private static string Format(double[] levels)
        {
            var builder = new StringBuilder(levels.Length * 7);
            for (int i = 0; i < levels.Length; i++)
            {
                if (i > 0) builder.Append(',');
                builder.Append(levels[i].ToString("F4", CultureInfo.InvariantCulture));
            }
            return builder.ToString();
        }

        internal void Reset()
        {
            filled = 0;
            referenceDb = double.NegativeInfinity;
            silentSeconds = 0.0;
            Array.Clear(windowA, 0, windowA.Length);
            Array.Clear(windowB, 0, windowB.Length);
        }

        internal string DescribeBands()
        {
            var builder = new StringBuilder();
            for (int b = 0; b < BandCount; b++)
            {
                if (b > 0) builder.Append('-');
                builder.Append(bandCenterHz[b].ToString("F0", CultureInfo.InvariantCulture));
            }
            return builder.ToString();
        }
    }
}

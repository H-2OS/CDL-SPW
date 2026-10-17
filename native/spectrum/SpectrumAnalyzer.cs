// SPDX-License-Identifier: GPL-3.0-only
//
// Super Island for SPW —— 进程回环频谱助手 · 信号分析层
// ============================================================================
//
// 输入：交错的多声道浮点样本（已归一化到 -1..1）。
// 输出：N 个频段的电平，0..1，供 Kotlin 侧直接喂给 `SpectralPainter`。
//
// ## 流水线
//
// ```
//   交错多声道样本
//     └─① 声道折叠 → A/B 两路单声道信号（单声道设备只有 A）
//          └─② 各自加窗 + 实数 FFT（N = 2048，帧间 50% 重叠）
//                └─③ 按对数频段边界累加功率 → 各频段 RMS（两路取 max）
//                      └─④ dB 域 + 全局自适应参考 → 0..1 电平
// ```
//
// 每一步的"为什么这么做"写在对应方法上。先交代两个**全局性**取舍。
//
// ## 取舍一：为什么用对数频段，不用线性频段
//
// 人耳对频率的感知是对数的：100→200 Hz 听感上"翻了一倍"，
// 5000→5100 Hz 几乎听不出区别。按线性分频段的话，音乐里信息量最大的
// 0–700 Hz 只会分到 1–2 根柱子，而 8–16 kHz 占掉一大片却几乎恒为 0 ——
// 画出来是"左边挤成一根、右边一排空"。
//
// 对数分频段让每根柱子覆盖大致相同的音程（这里约 0.7 个八度），
// 视觉上能量分布均匀得多。
//
// ## 取舍二：为什么电平是"相对参考"，不是绝对 dBFS
//
// 绝对 dBFS 下，正常聆听音量（约 -20 dBFS 峰值）会让所有柱子只到 1/3 高，
// 用户得为"看清频谱"而把音量开大 —— 而音量是为**听感**设的，
// 不该被可视化倒逼。故这里维护一个慢速自适应的参考电平，
// 把"当前响度"归一化到显示区间的上部。
//
// ⚠️ 关键设计：参考值是**全局的（所有频段共用）**，不是每频段各一个。
// 每频段独立归一化会把"高频天生比低频弱 20 dB"这一物理事实抹掉，
// 于是安静的高频段也顶到满格 —— 画出来像一排整齐的方波，**不反映音乐**。
// 共用一个参考能保住频段间的**相对能量**，而那正是频谱图要表达的信息。

using System;
using System.Globalization;
using System.Text;

namespace SuperIsland.Spectrum
{
    /// <summary>把一个播放进程的音频流变成频段电平。</summary>
    internal sealed class SpectrumAnalyzer
    {
        // ── 分析窗口 ──────────────────────────────────────────────────────

        /// <summary>
        /// FFT 点数。
        ///
        /// 取 2048 的取舍：44.1 kHz 下频率分辨率 = 44100/2048 ≈ 21.5 Hz，
        /// 于是最低频段（<see cref="MinFrequencyHz"/> = 45 Hz）至少能落到 1 个 bin 上。
        /// 再小（1024）分辨率掉到 43 Hz，最低两个频段会**共用一个 bin**，
        /// 表现为"左边两根柱子永远一模一样"。
        ///
        /// 代价是单帧时长约 46 ms —— 所以下面用 50% 重叠把**刷新率**补回来。
        /// </summary>
        private const int FftSize = 2048;

        /// <summary>帧间步进 = 半个窗口 → 50% 重叠，刷新率翻倍（≈43 行/秒 @44.1k）。</summary>
        private const int HopSize = FftSize / 2;

        /// <summary>
        /// 频段数。
        ///
        /// ⚠️ 它**只**影响本文件；Kotlin 侧按收到的个数自适应（见 `SpectrumSource`），
        /// 故改这个值不需要动任何 Java/Kotlin 代码。
        ///
        /// ## 2026-09-17：12 → **24**（用户点名）
        ///
        /// 用户原话：「柱状条要细一些，**覆盖的频率要完整一点**」。
        ///
        /// 12 段时每段覆盖约 **0.7 个八度** —— 以 45 Hz–16 kHz 计，
        /// 一段横跨的频率比一个大三度还宽，低频端尤其明显：
        /// 鼓与贝斯整个挤在左边两三根柱子里，"频率分布"这个信息基本丢了。
        /// 24 段把每段压到约 **0.35 个八度**，分辨率翻倍。
        ///
        /// ## 为什么不能无限加（这是本值的上限来源）
        ///
        /// FFT 是 2048 点，44.1 kHz 下 bin 宽 = 44100/2048 ≈ **21.5 Hz**，
        /// 而最低段下边界是 45 Hz（bin 2）。段数越多，最低那几段越容易
        /// **塌进同一个 bin** —— 表现为"左边几根柱子永远一模一样"。
        ///
        /// 实算（44.1 kHz）：24 段时最低段 45–57.5 Hz → bin 2–3，**仍有 1 个独占 bin**；
        /// 再往上（32 段 → 45–53.8 Hz → bin 2–2.5）就开始出现共用了。
        /// 故 **24 是"分辨率翻倍"与"最低段不塌"的平衡点**，不是随手取的值。
        /// 若将来把 <see cref="FftSize"/> 提到 4096，这个上限可以同步翻倍。
        /// </summary>
        internal const int BandCount = 24;

        /// <summary>最低频段下边界（Hz）。更低主要是直流漂移与次声，不表达音乐。</summary>
        private const double MinFrequencyHz = 45.0;

        /// <summary>最高频段上边界（Hz）。20 kHz 以上人耳听不到，且多数编码已切掉。</summary>
        private const double MaxFrequencyHz = 16000.0;

        // ── 电平映射 ──────────────────────────────────────────────────────

        /// <summary>
        /// 静音判定门限（dBFS）。低于它认为"这一段没有音乐"。
        ///
        /// 取 -72 而不是 -90：16 bit 理论底噪约 -96 dBFS，但真实回环流里
        /// 即使没有音乐也常有 -80 dB 量级的残留（引擎抖动、其他进程混入）。
        /// 门限太低会让静音时柱子仍在抖，看起来像坏了。
        /// </summary>
        private const double SilenceDb = -72.0;

        /// <summary>单频段噪声门（线性 RMS）。低于它的频段直接判 0，避免底噪被归一化成可见柱子。</summary>
        private const double NoiseFloorRms = 1e-5;

        /// <summary>
        /// 参考值上方的余量（dB）。
        ///
        /// 参考值直接取"当前峰值"的话，最响的频段会**恒定贴顶 1.0**，
        /// 于是"响"与"更响"在画面上没有区别（都被切成 1.0）。
        /// 抬高 6 dB 后，稳态下最响的频段落在约 (42-6)/42 ≈ 0.86 处，
        /// 真正的瞬态峰值才有空间冲到 1.0 —— "峰值"因此是**事件**而非常态。
        /// </summary>
        private const double HeadroomDb = 6.0;

        /// <summary>
        /// 显示动态范围（dB）。参考值往下这么多就是"柱子归零"的位置。
        ///
        /// 42 dB 的依据：流行/摇滚乐的频段间能量差常在 30–40 dB。
        /// 取 42 能让弱频段仍有可辨高度，又不至于让所有柱子挤在中部。
        /// </summary>
        private const double DynamicRangeDb = 42.0;

        /// <summary>
        /// 参考值**上升**时间常数（秒）。
        ///
        /// 上升必须快：鼓点进来时若参考值慢吞吞地跟，那一瞬间所有频段都会被
        /// 判成"远高于参考"而集体贴顶 —— 频谱变成"整排一起闪"，
        /// 而不是"低频那几根跳起来"。0.2 s 足以跟上一个乐句的响度变化，
        /// 又不会让单帧毛刺带动整条参考线。
        /// </summary>
        private const double ReferenceAttackSeconds = 0.20;

        /// <summary>
        /// 参考值**回落**时间常数（秒）。
        ///
        /// 回落必须慢：它代表"这首歌现在的响度水平"。落快了会在段落间隙
        /// （如主歌转副歌之间）把参考值拉得很低，于是副歌一进来整排柱子
        /// 瞬间贴顶 —— 画面"炸"一下，很廉价。
        /// </summary>
        private const double ReferenceReleaseSeconds = 3.0;

        /// <summary>连续静音多久后作废参考值（秒）。理由见 <see cref="Analyze"/>。</summary>
        private const double ReferenceForgetSeconds = 0.4;

        // ── 可变状态（全部只在抓取线程上访问，故无需同步）─────────────────

        /// <summary>
        /// A / B 两路的滑动窗口。
        ///
        /// ## 为什么用"尾部搬移"而不是真环形缓冲
        ///
        /// 每次分析完把后 <see cref="HopSize"/> 个样本挪到开头。每帧一次 8 KB
        /// 的 memmove（约 43 次/秒）代价可忽略，而它换来"窗口永远是一段连续内存"
        /// 这个**显然正确**的性质 —— 真环形缓冲的取模下标是这类代码里
        /// 最常见的 off-by-one 来源，而 off-by-one 在这里的表现是
        /// "频谱整体偏向某一侧"，极难一眼看出。
        ///
        /// ## 为什么 A/B 各一份窗口，而不是共用一个
        ///
        /// 两路是**独立**的信号，各自的 2048 点窗口必须分别累积。
        /// 曾写成"只留一份窗口、B 路样本丢弃"——那样 B 路拿到的仍是 A 的数据，
        /// 取 max 就退化成"等于 A"，反相内容照样被抵消（正是分两路要避免的事）。
        /// </summary>
        private readonly double[] windowA = new double[FftSize];
        private readonly double[] windowB = new double[FftSize];

        /// <summary>窗口里已有的样本数。达到 <see cref="FftSize"/> 才分析一次。</summary>
        private int filled;

        /// <summary>汉宁窗系数（预计算，避免每帧 2048 次 cos）。</summary>
        private readonly double[] hann = new double[FftSize];

        /// <summary>汉宁窗系数之和，用于 Parseval 归一化。见 <see cref="BandRms"/>。</summary>
        private readonly double windowGain;

        /// <summary>FFT 复数实部 / 虚部（复用，避免每帧分配）。</summary>
        private readonly double[] real = new double[FftSize];
        private readonly double[] imag = new double[FftSize];

        /// <summary>A / B 两路的频段 RMS 暂存（复用，避免每帧分配）。</summary>
        private readonly double[] rmsA = new double[BandCount];
        private readonly double[] rmsB = new double[BandCount];

        /// <summary>合并后的频段电平（复用）。</summary>
        private readonly double[] levelsScratch = new double[BandCount];

        /// <summary>每个频段的 bin 区间（左闭右开）。构造期按采样率算一次。</summary>
        private readonly int[] bandStart = new int[BandCount];
        private readonly int[] bandEnd = new int[BandCount];

        /// <summary>各频段中心频率，仅用于调试日志。</summary>
        private readonly double[] bandCenterHz = new double[BandCount];

        /// <summary>自适应参考电平（dBFS）。<c>NegativeInfinity</c> = 尚未建立。</summary>
        private double referenceDb = double.NegativeInfinity;

        /// <summary>已连续静音多久（秒）。</summary>
        private double silentSeconds;

        /// <summary>本分析器绑定的采样率（Hz）。</summary>
        private readonly double sampleRate;

        /// <summary>本分析器绑定的声道数。</summary>
        private readonly int channels;

        // ⚠️ 用"私有字段 + 显式 getter"而不是 C# 6 的只读自动属性
        //    （`{ get; }`）—— 后者系统自带的 csc.exe 编不过。见 buildSpectrum 任务。
        internal double SampleRate { get { return sampleRate; } }

        internal int Channels { get { return channels; } }

        internal SpectrumAnalyzer(int sampleRate, int channels)
        {
            this.sampleRate = sampleRate;
            this.channels = channels < 1 ? 1 : channels;

            // 汉宁窗：w[i] = 0.5 - 0.5·cos(2πi/(N-1))。
            //
            // 为什么用汉宁而不是矩形窗：矩形窗旁瓣衰减只有约 -13 dB，
            // 强低频（贝斯/底鼓）会"漏"到相邻高频段上，表现为低频一响、
            // 右边整排柱子跟着亮。汉宁窗旁瓣约 -31 dB 且衰减快，
            // 代价是主瓣变宽（频率分辨率下降约 1.5 倍）—— 对可视化完全可接受。
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

        /// <summary>
        /// 按对数间距把 <see cref="MinFrequencyHz"/>..<see cref="MaxFrequencyHz"/>
        /// 划成 <see cref="BandCount"/> 段，并换算成 bin 下标。
        ///
        /// 边界频率用等比数列：<c>edge[b] = f0 · (f1/f0)^(b/N)</c>。
        /// 每段频率比相同 → 每段覆盖相同音程。
        ///
        /// ⚠️ 相邻段必须**共享边界**。这里两段的 start/end 由同一个公式
        /// 各推一次，若各自四舍五入就可能差 1 个 bin（漏掉或重复一段频率）。
        /// 故下面显式把 <c>bandStart[b]</c> 与上一段的 <c>bandEnd[b-1]</c> 对齐。
        /// </summary>
        private void BuildBands()
        {
            double ratio = Math.Pow(MaxFrequencyHz / MinFrequencyHz, 1.0 / BandCount);
            for (int b = 0; b < BandCount; b++)
            {
                double lowHz = MinFrequencyHz * Math.Pow(ratio, b);
                double highHz = lowHz * ratio;

                // 下限 clamp 到 1：bin 0 是直流分量，包含整个信号的均值
                // （回环流里常有非零偏置）。算进任何频段都会让那根柱子
                // 无理由地常年偏亮。
                int start = Math.Max(1, (int)Math.Round(lowHz * FftSize / SampleRate));
                // 上限 clamp 到 Nyquist（FftSize/2，不含）。
                int end = Math.Min(FftSize / 2, (int)Math.Round(highHz * FftSize / SampleRate));

                bandStart[b] = start;
                bandEnd[b] = Math.Max(start + 1, end);
                bandCenterHz[b] = Math.Sqrt(lowHz * highHz); // 对数中点（几何平均）
            }
            // 逐段强制连续：本段的起点 = 上一段的终点。见上面的 ⚠️。
            for (int b = 1; b < BandCount; b++)
            {
                if (bandStart[b] < bandEnd[b - 1]) bandStart[b] = bandEnd[b - 1];
            }
        }

        /// <summary>
        /// 送入一批**交错**样本。返回本次新算出的电平行；没有新结果时返回 <c>null</c>。
        ///
        /// 返回 <c>null</c> 而不是"上一行的副本"，是为了让调用方
        /// （<see cref="SpectrumProcess"/>）天然地"只在有新数据时写一行" ——
        /// 协议里"行"就是一次更新，重复写旧值只是浪费管道带宽。
        /// </summary>
        /// <param name="interleaved">交错样本，长度须为 <see cref="Channels"/> 的整数倍。</param>
        /// <param name="count">有效样本个数。</param>
        /// <param name="isSilent">引擎标记"这一段是静音填充"。为 true 时按零处理。</param>
        internal string Push(float[] interleaved, int count, bool isSilent)
        {
            string last = null;
            int frames = count / Channels;
            for (int f = 0; f < frames; f++)
            {
                int baseIndex = f * Channels;

                // ── ① 声道折叠：偶/奇两组分别平均 ──────────────────────────
                //
                // 为什么不把所有声道平均成一路：那样**反相的立体声内容会互相抵消**
                // （左右同信号反相是常见的效果处理）。分成两组后，即便两组彼此反相，
                // 各自的功率谱仍然正确，再在功率域取 max（见 Analyze）就能保住能量 ——
                // 相位信息在功率谱里本来就不存在。
                //
                // 单声道设备：A 路 = 该声道，B 路 = 同一个值（两路相同，
                // 取 max 不改变结果，也不需要特判）。
                double a = 0.0, b = 0.0;
                int aCount = 0, bCount = 0;
                for (int c = 0; c < Channels; c++)
                {
                    double v = isSilent ? 0.0 : interleaved[baseIndex + c];
                    if ((c & 1) == 0) { a += v; aCount++; } else { b += v; bCount++; }
                }
                a = aCount > 0 ? a / aCount : 0.0;
                b = bCount > 0 ? b / bCount : a;

                // ── ② 推进窗口 ────────────────────────────────────────────
                windowA[filled] = a;
                windowB[filled] = b;
                filled++;
                if (filled < FftSize) continue;

                // 窗口满了 → 分析一次，然后把尾部 HopSize 个样本挪到开头（50% 重叠）。
                last = Analyze();

                Array.Copy(windowA, HopSize, windowA, 0, FftSize - HopSize);
                Array.Copy(windowB, HopSize, windowB, 0, FftSize - HopSize);
                filled = FftSize - HopSize;
            }
            return last;
        }

        /// <summary>
        /// 分析当前窗口，返回各频段电平（0..1）。静音时返回全 0。
        /// </summary>
        private string Analyze()
        {
            double dt = (double)HopSize / SampleRate;

            // ── ③ 两路各自的频段 RMS，再逐频段取 max ──────────────────────
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

            // ── ④ 静音判定 ────────────────────────────────────────────────
            if (peakDb < SilenceDb)
            {
                silentSeconds += dt;
                if (silentSeconds >= ReferenceForgetSeconds)
                {
                    // 作废参考值：下一段音乐进来时重新建立，而不是拿"上一段的响度"
                    // 去衡量它。不清零的话，从很响的副歌切到安静的前奏时，
                    // 前奏会被判成"远低于参考"而几乎不可见。
                    referenceDb = double.NegativeInfinity;
                }
                return Format(new double[BandCount]);
            }
            silentSeconds = 0.0;
            // ── ⑤ 参考值自适应 ────────────────────────────────────────────
            if (double.IsNegativeInfinity(referenceDb))
            {
                // 首次建立：**直接采用**当前峰值，不做渐入。
                // 渐入的话，用户刚打开开关的一两秒会看到"柱子从满格缩下来"
                // （参考值从 0 涨到真实峰值的过程中，所有频段都被判成"很响"），
                // 看起来像 bug。
                referenceDb = peakDb;
            }
            else
            {
                double tau = peakDb > referenceDb ? ReferenceAttackSeconds : ReferenceReleaseSeconds;
                referenceDb += (peakDb - referenceDb) * (1.0 - Math.Exp(-dt / tau));
            }

            // ── ⑥ 映射到 0..1 ─────────────────────────────────────────────
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

        /// <summary>
        /// 对 <paramref name="source"/>（一个完整窗口）做加窗 + FFT，
        /// 把各频段 RMS 写进 <paramref name="destination"/>。
        ///
        /// ## 归一化推导（**本文件最容易写错的一处**，故完整写出）
        ///
        /// 设输入是幅度 A 的单频正弦、恰好落在某个 bin 中心 k0 上，则
        /// <code>
        ///     |X[k0]| = (A/2) · Σ w[n] = (A/2) · W        （W = windowGain）
        ///     |X[k0]|² = (A²/4) · W²
        /// </code>
        /// 而该正弦的 RMS 是 A/√2，故 RMS² = A²/2。两式消去 A²：
        /// <code>
        ///     RMS² = 2 · |X[k0]|² / W²
        /// </code>
        /// 这就是下面 <c>2·power/(W·W)</c> 的来源。它比"用 Σw² 做能量归一化"
        /// 更适合**显示**：给出的是"这一频段里正弦分量的实际 RMS"，
        /// 与 dBFS 有直接的物理对应，于是 <see cref="SilenceDb"/> 之类的
        /// 门限才有确定的含义。
        ///
        /// 注：落在两个 bin 之间的正弦会有"扇贝损失"（最多约 -1.4 dB），
        /// 对可视化无影响，故不做插值补偿。
        /// </summary>
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

        /// <summary>
        /// 原地基-2 时域抽取 FFT（实数输入，虚部全 0 起步）。
        ///
        /// 两趟：
        /// ① **位反转置换** —— 把输入按二进制下标倒序重排，使后面的蝶形可以
        ///    在连续内存上原地做（无递归、无额外缓冲）。
        /// ② **逐级蝶形** —— 每级长度翻倍，用旋转因子把两个半长 DFT 合成一个。
        ///
        /// ## 为什么旋转因子用 cos/sin 直接算，而不是"递推"
        ///
        /// 递推（<c>w *= w_step</c>）看起来更省，但每次复乘都引入一次舍入误差，
        /// 而误差会随级数累积 —— 2048 点下高频端的相位误差可达 1e-3 量级，
        /// 表现为高频频段读数轻微抖动。直接对每个 k 算 <c>cos(ang·k)</c> 是精确的，
        /// 而整帧 FFT 实测远低于 1 ms —— 这个取舍与"刷新率 43 Hz"是同一个判断：
        /// 这里省下的**复杂度**比省下的 CPU 值钱得多。
        /// </summary>
        private void ForwardFft()
        {
            // ① 位反转置换
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

            // ② 逐级蝶形
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

        /// <summary>
        /// 把电平格式化成一行 <c>0.1234,0.5678,…</c>。
        ///
        /// ⚠️ **必须用 <see cref="CultureInfo.InvariantCulture"/>**：
        /// 在德语/法语等区域设置下 <c>ToString("F4")</c> 会输出 <c>0,1234</c>
        /// （逗号作小数点），于是这一行会被 Kotlin 侧按逗号切成两半、
        /// 解析出一堆错位的频段。这个缺陷只在特定区域设置下复现，
        /// 在中文/英文开发机上**永远看不到**。
        /// </summary>
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

        /// <summary>重置全部分析状态（格式重建 / 长时间静音后由 <see cref="SpectrumProcess"/> 调用）。</summary>
        internal void Reset()
        {
            filled = 0;
            referenceDb = double.NegativeInfinity;
            silentSeconds = 0.0;
            Array.Clear(windowA, 0, windowA.Length);
            Array.Clear(windowB, 0, windowB.Length);
        }

        /// <summary>调试用：各频段中心频率，形如 <c>45-74-…</c>。只进日志，不进协议。</summary>
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

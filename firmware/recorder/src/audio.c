/* 音频：PDM 麦克风 16kHz → 软件增益 → Opus 编码 → BLE 发送。
 * 只在手机订阅了音频时才开麦，省电。
 */
#include <zephyr/kernel.h>
#include <zephyr/audio/dmic.h>
#include <zephyr/drivers/gpio.h>
#include <opus.h>
#include "app.h"

#define BLOCK_BYTES  (FRAME_SAMPLES * 2)
#define BLOCK_COUNT  8                    /* 8 × 20ms = 160ms 缓冲 */
#define SW_GAIN      4                    /* 数字增益 ×4 (+12dB)，实测原始电平偏低 */
#define STATS_EVERY  250                  /* 每 250 帧 (5 秒) 打印一次统计 */

K_MEM_SLAB_DEFINE_STATIC(pcm_slab, BLOCK_BYTES, BLOCK_COUNT, 4);

static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct device *dmic = DEVICE_DT_GET(DT_NODELABEL(dmic_dev));

static uint8_t enc_mem[28 * 1024] __aligned(8);
static OpusEncoder *enc;

static int mic_config(void)
{
	static struct pcm_stream_cfg stream = {
		.pcm_rate = SAMPLE_RATE, .pcm_width = 16,
		.block_size = BLOCK_BYTES, .mem_slab = &pcm_slab,
	};
	struct dmic_cfg cfg = {
		.io = { .min_pdm_clk_freq = 1000000, .max_pdm_clk_freq = 3500000,
			.min_pdm_clk_dc = 40, .max_pdm_clk_dc = 60 },
		.streams = &stream,
		.channel = { .req_num_streams = 1, .req_num_chan = 1,
			     .req_chan_map_lo = dmic_build_channel_map(0, 0, PDM_CHAN_LEFT) },
	};
	return dmic_configure(dmic, &cfg);
}

static int encoder_init(void)
{
	int size = opus_encoder_get_size(1);
	printk("opus encoder size: %d bytes\n", size);
	if (size > sizeof(enc_mem)) {
		return -ENOMEM;
	}
	enc = (OpusEncoder *)enc_mem;
	int err = opus_encoder_init(enc, SAMPLE_RATE, 1, OPUS_APPLICATION_VOIP);
	if (err != OPUS_OK) {
		return err;
	}
	opus_encoder_ctl(enc, OPUS_SET_BITRATE(OPUS_BITRATE));
	opus_encoder_ctl(enc, OPUS_SET_VBR(1));
	opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(1));
	opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
	opus_encoder_ctl(enc, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND));
	return 0;
}

static void apply_gain(int16_t *s, int n)
{
	for (int i = 0; i < n; i++) {
		int32_t v = (int32_t)s[i] * SW_GAIN;
		s[i] = CLAMP(v, INT16_MIN, INT16_MAX);
	}
}

static void drain_mic(void)
{
	void *buf;
	uint32_t size;

	while (dmic_read(dmic, 0, &buf, &size, 0) == 0) {
		k_mem_slab_free(&pcm_slab, buf);
	}
}

static void stream_session(void)
{
	uint8_t out[OPUS_MAX_BYTES];
	uint32_t frames = 0, bytes = 0, send_err = 0, enc_us_sum = 0, enc_us_max = 0;

	if (dmic_trigger(dmic, DMIC_TRIGGER_START) < 0) {
		printk("ERROR: mic start failed\n");
		k_msleep(1000);
		return;
	}
	gpio_pin_set_dt(&led, 1);
	printk("mic ON\n");

	while (ble_streaming()) {
		void *buf;
		uint32_t size;

		if (dmic_read(dmic, 0, &buf, &size, 500) < 0) {
			printk("mic read timeout\n");
			continue;
		}
		apply_gain(buf, size / 2);

		uint32_t t0 = k_cycle_get_32();
		int n = opus_encode(enc, buf, FRAME_SAMPLES, out, sizeof(out));
		uint32_t us = k_cyc_to_us_floor32(k_cycle_get_32() - t0);
		k_mem_slab_free(&pcm_slab, buf);

		if (n < 0) {
			printk("opus error %d\n", n);
			continue;
		}
		if (ble_send_audio(out, n) != 0) {
			send_err++;
		}

		frames++;
		bytes += n;
		enc_us_sum += us;
		enc_us_max = MAX(enc_us_max, us);
		if (frames % STATS_EVERY == 0) {
			printk("[stats] %u frames, %u B/s, encode avg %u us max %u us, send errors %u\n",
			       frames, bytes * 1000 / (STATS_EVERY * FRAME_MS),
			       enc_us_sum / STATS_EVERY, enc_us_max, send_err);
			bytes = enc_us_sum = enc_us_max = 0;
		}
	}

	dmic_trigger(dmic, DMIC_TRIGGER_STOP);
	drain_mic();
	gpio_pin_set_dt(&led, 0);
	printk("mic OFF\n");
}

static void audio_thread(void *a, void *b, void *c)
{
	gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);

	if (!device_is_ready(dmic) || mic_config() < 0) {
		printk("ERROR: mic init failed\n");
		return;
	}
	if (encoder_init() != 0) {
		printk("ERROR: opus init failed\n");
		return;
	}
	printk("audio ready, waiting for phone to subscribe\n");

	for (;;) {
		if (ble_streaming()) {
			stream_session();
		} else {
			k_msleep(100);
		}
	}
}

/* Opus 用变长数组放在栈上，需要较大的栈 */
K_THREAD_STACK_DEFINE(audio_stack, 32 * 1024);
static struct k_thread audio_tid;

void audio_start(void)
{
	k_thread_create(&audio_tid, audio_stack, K_THREAD_STACK_SIZEOF(audio_stack),
			audio_thread, NULL, NULL, NULL, 5, K_FP_REGS, K_NO_WAIT);
}

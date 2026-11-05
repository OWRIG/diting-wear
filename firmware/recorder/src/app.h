#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/* 采样参数 */
#define SAMPLE_RATE    16000
#define FRAME_MS       20
#define FRAME_SAMPLES  (SAMPLE_RATE * FRAME_MS / 1000)   /* 320 */
#define OPUS_BITRATE   16000
#define OPUS_MAX_BYTES 120

/* ble.c */
int ble_init(void);
bool ble_streaming(void);              /* 已连接且手机订阅了音频 */
int ble_send_audio(const uint8_t *data, size_t len);

/* audio.c */
void audio_start(void);

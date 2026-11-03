#ifndef WHISPER_H
#define WHISPER_H

#include "ggml.h"
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define WHISPER_SAMPLE_RATE 16000
#define WHISPER_N_FFT 400
#define WHISPER_HOP_LENGTH 160
#define WHISPER_CHUNK_SIZE 30

struct whisper_context;

enum whisper_sampling_strategy {
    WHISPER_SAMPLING_GREEDY,
    WHISPER_SAMPLING_BEAM_SEARCH,
};

struct whisper_context_params {
    bool use_gpu;
};

struct whisper_full_params {
    enum whisper_sampling_strategy strategy;
    int n_threads;
    int offset_ms;
    int duration_ms;
    bool translate;
    bool no_context;
    bool single_segment;
    bool print_special;
    bool print_progress;
    bool print_realtime;
    bool print_timestamps;
    const char * language;
};

struct whisper_context_params whisper_context_default_params();
struct whisper_full_params whisper_full_default_params(enum whisper_sampling_strategy strategy);

struct whisper_context * whisper_init_from_file_with_params(const char * path_model, struct whisper_context_params params);
void whisper_free(struct whisper_context * ctx);

int whisper_full(struct whisper_context * ctx, struct whisper_full_params params, const float * samples, int n_samples);
int whisper_full_n_segments(struct whisper_context * ctx);
const char * whisper_full_get_segment_text(struct whisper_context * ctx, int i_segment);
int64_t whisper_full_get_segment_t0(struct whisper_context * ctx, int i_segment);
int64_t whisper_full_get_segment_t1(struct whisper_context * ctx, int i_segment);

#ifdef __cplusplus
}
#endif

#endif // WHISPER_H

#include "ggml.h"
#include <stdlib.h>
#include <string.h>

struct ggml_context {
    size_t mem_size;
    void * mem_buffer;
};

struct ggml_context * ggml_init(struct ggml_init_params params) {
    struct ggml_context * ctx = (struct ggml_context *)malloc(sizeof(struct ggml_context));
    if (!ctx) return NULL;
    ctx->mem_size = params.mem_size;
    ctx->mem_buffer = params.mem_buffer;
    return ctx;
}

void ggml_free(struct ggml_context * ctx) {
    if (ctx) {
        free(ctx);
    }
}

size_t ggml_type_size(enum ggml_type type) {
    switch (type) {
        case GGML_TYPE_F32: return 4;
        case GGML_TYPE_F16: return 2;
        case GGML_TYPE_Q4_0: return 2;
        case GGML_TYPE_Q4_1: return 2;
        case GGML_TYPE_Q8_0: return 2;
        case GGML_TYPE_I8: return 1;
        case GGML_TYPE_I16: return 2;
        case GGML_TYPE_I32: return 4;
        default: return 4;
    }
}

size_t ggml_element_size(const struct ggml_tensor * tensor) {
    return ggml_type_size(tensor ? tensor->type : GGML_TYPE_F32);
}

#include "ggml-alloc.h"
#include <stdlib.h>

struct ggml_gallocr {
    enum ggml_type type;
};

ggml_gallocr_t ggml_gallocr_new(enum ggml_type type) {
    struct ggml_gallocr * galloc = (struct ggml_gallocr *)malloc(sizeof(struct ggml_gallocr));
    if (galloc) {
        galloc->type = type;
    }
    return galloc;
}

void ggml_gallocr_free(ggml_gallocr_t galloc) {
    if (galloc) {
        free(galloc);
    }
}

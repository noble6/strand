# Host tests for the Resona effect core. Same flags as the Resona engine.
CFLAGS ?= -O2 -ffast-math -fno-finite-math-only
override CFLAGS += -std=gnu11 -Wall -Wextra -Werror -Wno-unused-function -pthread

run: fx_test
	./fx_test
fx_test: fx_test.c ../effect/resona_fx.c ../effect/resona_fx.h ../resona/resona_dsp.c
	$(CC) $(CFLAGS) -o $@ fx_test.c ../effect/resona_fx.c -lm
tsan:
	$(MAKE) -B fx_test CFLAGS="-O1 -g -fsanitize=thread -Wno-error=tsan"
	./fx_test
asan:
	$(MAKE) -B fx_test CFLAGS="-O1 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined"
	./fx_test
# The effect library as the audio HAL loads it (needs AOSP headers in AOSP_INC
# and a log/log.h, e.g. -I with ALOGx defined to printf)
glue: glue_test libresonafx.so
	./glue_test ./libresonafx.so
libresonafx.so: ../effect/resona_effect.c ../effect/resona_fx.c ../resona/resona_dsp.c
	$(CC) $(CFLAGS) -shared -fPIC -fvisibility=hidden $(AOSP_INC) -o $@ ../effect/resona_effect.c ../effect/resona_fx.c -lm
glue_test: glue_test.c
	$(CC) $(CFLAGS) $(AOSP_INC) -o $@ $< -ldl -lm
clean:
	rm -f fx_test glue_test libresonafx.so
.PHONY: run tsan asan glue clean

/* SPDX-License-Identifier: GPL-3.0-only */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../../src/api-impl-jni/media/MediaCodecConfig.h"

int main(void)
{
	const uint8_t sps[] = {0x67, 0x64, 0x00, 0x1f};
	const uint8_t pps[] = {0x68, 0xee, 0x3c};
	uint8_t combined[sizeof(sps) + sizeof(pps)] = {0};

	if (!atl_copy_codec_config(combined, sizeof(combined), 0, sps, sizeof(sps)) ||
	    !atl_copy_codec_config(combined, sizeof(combined), sizeof(sps), pps, sizeof(pps)) ||
	    memcmp(combined, sps, sizeof(sps)) != 0 ||
	    memcmp(combined + sizeof(sps), pps, sizeof(pps)) != 0) {
		fprintf(stderr, "FAIL: SPS/PPS buffers must be copied with their own lengths\n");
		return 1;
	}

	uint8_t before[sizeof(combined)];
	memcpy(before, combined, sizeof(before));
	if (atl_copy_codec_config(combined, sizeof(combined), sizeof(sps), pps, sizeof(combined)) ||
	    memcmp(combined, before, sizeof(combined)) != 0) {
		fprintf(stderr, "FAIL: out-of-bounds codec config copy must be rejected without mutation\n");
		return 1;
	}

	puts("PASS: separate SPS/PPS lengths and destination bounds");
	return 0;
}

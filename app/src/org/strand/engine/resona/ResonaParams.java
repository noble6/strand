// SPDX-License-Identifier: GPL-3.0-or-later
package org.strand.engine.resona;

/**
 * Engine parameter indices, in resona_shm.h order (audio/tests/check_params.py
 * checks this file against the header), and the preset key for each.
 */
final class ResonaParams {
    private ResonaParams() {}

    static final int ENABLE = 0;
    static final int PREAMP = 1;
    static final int OUTPUT = 2;
    static final int EQ_ON = 3;
    static final int EQ0 = 4;
    static final int EQ1 = 5;
    static final int EQ2 = 6;
    static final int EQ3 = 7;
    static final int EQ4 = 8;
    static final int EQ5 = 9;
    static final int EQ6 = 10;
    static final int EQ7 = 11;
    static final int EQ8 = 12;
    static final int EQ9 = 13;
    static final int BASS_ON = 14;
    static final int BASS_AMT = 15;
    static final int BASS_FREQ = 16;
    static final int BASS_PUNCH = 17;
    static final int CLAR_ON = 18;
    static final int CLAR_AMT = 19;
    static final int VOICE_ON = 20;
    static final int VOICE_AMT = 21;
    static final int STAGE_ON = 22;
    static final int STAGE_WIDTH = 23;
    static final int STAGE_DEPTH = 24;
    static final int XFEED_ON = 25;
    static final int XFEED_AMT = 26;
    static final int AMB_ON = 27;
    static final int AMB_MIX = 28;
    static final int AMB_SIZE = 29;
    static final int AMB_DAMP = 30;
    static final int LEV_ON = 31;
    static final int LEV_AMT = 32;
    static final int LEV_TARGET = 33;
    static final int LOUD_ON = 34;
    static final int LOUD_AMT = 35;
    static final int LIM_ON = 36;
    static final int LIM_CEIL = 37;
    static final int SYSVOL_DB = 38;
    static final int IEQ_ON = 39;
    static final int IEQ_AMT = 40;
    static final int IEQ_TARGET = 41;
    static final int TUBE_ON = 42;
    static final int TUBE_DRIVE = 43;
    static final int HRTF_ON = 44;
    static final int HRTF_ANGLE = 45;
    static final int HRTF_AMT = 46;
    static final int CORR_ON = 47;
    static final int CORR_PREAMP = 48;
    static final int UPMIX_ON = 49;
    static final int UPMIX_CENTER = 50;
    static final int UPMIX_SURR = 51;
    static final int UPMIX_LFE = 52;
    static final int LAYOUT = 53;
    static final int BASS_TYPE = 54;
    static final int COUNT = 55;

    /** Preset keys (resona app/resona/presets.py) by index. */
    static final String[] KEYS = {
        "enable",
        "preamp",
        "output",
        "eq_on",
        "eq0",
        "eq1",
        "eq2",
        "eq3",
        "eq4",
        "eq5",
        "eq6",
        "eq7",
        "eq8",
        "eq9",
        "bass_on",
        "bass_amt",
        "bass_freq",
        "bass_punch",
        "clar_on",
        "clar_amt",
        "voice_on",
        "voice_amt",
        "stage_on",
        "stage_width",
        "stage_depth",
        "xfeed_on",
        "xfeed_amt",
        "amb_on",
        "amb_mix",
        "amb_size",
        "amb_damp",
        "lev_on",
        "lev_amt",
        "lev_target",
        "loud_on",
        "loud_amt",
        "lim_on",
        "lim_ceil",
        "sysvol_db",
        "ieq_on",
        "ieq_amt",
        "ieq_target",
        "tube_on",
        "tube_drive",
        "hrtf_on",
        "hrtf_angle",
        "hrtf_amt",
        "corr_on",
        "corr_preamp",
        "upmix_on",
        "upmix_center",
        "upmix_surr",
        "upmix_lfe",
        "layout",
        "bass_type",
    };

    static int index(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }
}

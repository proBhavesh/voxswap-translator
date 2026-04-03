package nie.translator.vtranslator;

import ai.onnxruntime.OrtSession;

public final class DebugConfig {
    private DebugConfig() {}

    /** Enable ONNX Runtime per-operator profiling (writes JSON to app files dir) */
    public static final boolean ENABLE_ONNX_PROFILING = true;

    /**
     * ONNX graph optimization level for all model sessions.
     * NO_OPT = no optimization (current best on ARM mobile)
     * BASIC_OPT = constant folding, redundant node elimination
     * EXTENDED_OPT = attention/layernorm/GELU fusion (slower on ARM, faster on x86)
     * ALL_OPT = layout transforms (risky on mobile)
     */
    public static final OrtSession.SessionOptions.OptLevel ONNX_OPT_LEVEL =
            OrtSession.SessionOptions.OptLevel.NO_OPT;
}

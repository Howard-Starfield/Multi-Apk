package top.niunaijun.blackbox.utils;

import android.app.ActivityOptions;
import android.content.Context;
import android.os.Bundle;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.R;

/** The transition used whenever a clone is brought to the front: fade plus a slight grow from the centre. */
public final class SwitchAnimation {
    private SwitchAnimation() {
    }

    /** Activity options carrying the transition, or null if they cannot be built. */
    public static Bundle options() {
        try {
            Context context = BlackBoxCore.getContext();
            return ActivityOptions.makeCustomAnimation(context, R.anim.bb_clone_enter, R.anim.bb_clone_exit).toBundle();
        } catch (Throwable t) {
            return null;
        }
    }
}

package top.niunaijun.blackbox.fake.delegate;

import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import black.android.content.BRIIntentReceiver;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.proxy.record.ProxyBroadcastRecord;


public class InnerReceiverDelegate extends IIntentReceiver.Stub {
    public static final String TAG = "InnerReceiverDelegate";

    private static final Map<IBinder, InnerReceiverDelegate> sInnerReceiverDelegate = new HashMap<>();
    private final WeakReference<IIntentReceiver> mIntentReceiver;

    private InnerReceiverDelegate(IIntentReceiver iIntentReceiver) {
        this.mIntentReceiver = new WeakReference<>(iIntentReceiver);
    }

    public static InnerReceiverDelegate getDelegate(IBinder iBinder) {
        return sInnerReceiverDelegate.get(iBinder);
    }

    public static IIntentReceiver createProxy(IIntentReceiver base) {
        if (base instanceof InnerReceiverDelegate) {
            return base;
        }
        final IBinder iBinder = base.asBinder();
        InnerReceiverDelegate delegate = sInnerReceiverDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sInnerReceiverDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new InnerReceiverDelegate(base);
            sInnerReceiverDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
        intent.setExtrasClassLoader(BlackBoxCore.getApplication().getClassLoader());
        ProxyBroadcastRecord proxyBroadcastRecord = ProxyBroadcastRecord.create(intent);
        Intent perIntent;
        if (proxyBroadcastRecord.mIntent != null) {
            proxyBroadcastRecord.mIntent.setExtrasClassLoader(BlackBoxCore.getApplication().getClassLoader());
            perIntent = proxyBroadcastRecord.mIntent;
            // Sent by a clone: every clone registers its receivers under the host package, so the
            // system matches by action alone. Deliver only inside the sender's own virtual user.
            if (proxyBroadcastRecord.mUserId != BActivityThread.getUserId()) {
                if (ordered) {
                    finishSkipped(resultCode, data, extras, intent.getFlags());
                }
                return;
            }
        } else {
            perIntent = intent;
        }
        IIntentReceiver iIntentReceiver = mIntentReceiver.get();
        if (iIntentReceiver != null) {
            BRIIntentReceiver.get(iIntentReceiver).performReceive(perIntent, resultCode, data, extras, ordered, sticky, sendingUser);
        }
    }

    /** Lets an ordered broadcast move on when this receiver skips it. */
    private void finishSkipped(int resultCode, String data, Bundle extras, int flags) {
        try {
            Object am;
            try {
                am = android.app.ActivityManager.class.getMethod("getService").invoke(null);
            } catch (NoSuchMethodException e) {
                // API 21-25
                am = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            }
            for (java.lang.reflect.Method m : am.getClass().getMethods()) {
                if (m.getName().equals("finishReceiver") && m.getParameterTypes().length == 6) {
                    m.invoke(am, this, resultCode, data, extras, false, flags);
                    return;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}

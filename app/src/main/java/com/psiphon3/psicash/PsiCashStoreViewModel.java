package com.psiphon3.psicash;

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.content.Context;
import android.support.annotation.NonNull;

import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;

import io.reactivex.Single;

public class PsiCashStoreViewModel extends AndroidViewModel {
    private TunnelServiceInteractor tunnelServiceInteractor;

    public PsiCashStoreViewModel(@NonNull Application application) {
        super(application);
        tunnelServiceInteractor = new TunnelServiceInteractor(application.getApplicationContext());
    }

    TunnelServiceInteractor getTunnelServiceInteractor() {
        return tunnelServiceInteractor;
    }

    Single<PsiCashClient> getPsiCashClientSingle(Context context) {
        return Single.fromCallable(() -> PsiCashClient.getInstance(context));
    }
}

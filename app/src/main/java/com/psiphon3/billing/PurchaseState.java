package com.psiphon3.billing;

import com.android.billingclient.api.Purchase;
import com.google.auto.value.AutoValue;

import io.reactivex.annotations.Nullable;

@AutoValue
public abstract class PurchaseState {
    @Nullable
    public abstract Purchase purchase();
    static PurchaseState create(Purchase purchase) {
        return new AutoValue_PurchaseState(purchase);
    }
    static PurchaseState empty() {
        return new AutoValue_PurchaseState(null);
    }
}



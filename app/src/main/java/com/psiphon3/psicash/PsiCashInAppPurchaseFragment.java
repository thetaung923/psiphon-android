package com.psiphon3.psicash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.transition.Scene;
import android.support.transition.TransitionManager;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.text.NumberFormat;
import java.util.Collections;

import io.reactivex.Observable;

public class PsiCashInAppPurchaseFragment extends Fragment {
    private GooglePlayBillingHelper googlePlayBillingHelper;

    private Scene sceneBuyPsiCash;
    private Scene sceneUnfinishedPsiCashPurchase;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Context ctx = getContext();
        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(ctx);

        View view = inflater.inflate(R.layout.psicash_store_scene_container_fragment, container, false);

        ViewGroup sceneRoot = view.findViewById(R.id.scene_root);
        View progressOverlay = view.findViewById(R.id.progress_overlay);

        sceneBuyPsiCash = Scene.getSceneForLayout(sceneRoot, R.layout.buy_psicash_scene, ctx);
        sceneUnfinishedPsiCashPurchase = Scene.getSceneForLayout(sceneRoot, R.layout.buy_psicash_unfinished_purchase_scene, ctx);

        googlePlayBillingHelper.purchaseStateFlowable()
                .distinctUntilChanged()
                .doOnNext(purchaseState -> {
                    final Purchase purchase = purchaseState.purchase();
                    if (purchase != null && GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                        sceneUnfinishedPsiCashPurchase.setEnterAction(
                                unfinishedPurchaseEnterAction(progressOverlay, view, purchase));
                        TransitionManager.go(sceneUnfinishedPsiCashPurchase);
                    } else {
                        sceneBuyPsiCash.setEnterAction(
                                buyPsiCashEnterAction(progressOverlay, inflater, container, view));
                        TransitionManager.go(sceneBuyPsiCash);
                    }
                })
                .subscribe();

        return view;
    }

    private Runnable unfinishedPurchaseEnterAction(View progressOverlay, View view, Purchase unfinishedPurchase) {
        return () -> {
            progressOverlay.setVisibility(View.GONE);
            TextView tv = view.findViewById(R.id.unfinishedPurchaseTitle);
            tv.setText(unfinishedPurchase.getOrderId());
            Button connectBtn = view.findViewById(R.id.connect_psiphon_btn);
            connectBtn.setOnClickListener(v -> {
                final Activity activity = getActivity();
                try {
                    Intent data = new Intent();
                    data.putExtra(PsiCashStoreActivity.SPEEDBOOST_CONNECT_PSIPHON_EXTRA, true);
                    activity.setResult(Activity.RESULT_OK, data);
                    activity.finish();
                } catch (NullPointerException ignored) {
                }
            });
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        googlePlayBillingHelper.queryAllSkuDetails();
        googlePlayBillingHelper.queryAllPurchases();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private Runnable buyPsiCashEnterAction(View progressOverlay, LayoutInflater inflater, ViewGroup container, View view) {
        return () -> googlePlayBillingHelper.allSkuDetailsSingle()
                .toObservable()
                .flatMap(Observable::fromIterable)
                .filter(skuDetails -> {
                    String sku = skuDetails.getSku();
                    return GooglePlayBillingHelper.IAB_PSICASH_SKUS_TO_VALUE.containsKey(sku);
                })
                .toList()
                .doOnSuccess(skuDetailsList -> {
                    progressOverlay.setVisibility(View.GONE);

                    LinearLayout containerLayout = view.findViewById(R.id.psicash_purchase_options_container);

                    // Add "Watch ad to earn 35 PsiCash" button
                    View psicashPurchaseItemView = inflater.inflate(R.layout.psicash_purchase_template, container, false);

                    ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_title)).setText(R.string.psicash_purchase_free_name);
                    ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_description)).setText(R.string.psicash_purchase_free_description);

                    Button btn = psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_price);
                    btn.setText(R.string.psicash_purchase_free_button_price);
                    btn.setOnClickListener(v -> {
                        final Activity activity = getActivity();
                        try {
                            Intent data = new Intent();
                            data.putExtra(PsiCashStoreActivity.PURCHASE_PSICASH_GET_FREE, true);
                            activity.setResult(Activity.RESULT_OK, data);
                            activity.finish();
                        } catch (NullPointerException e) {
                        }
                    });

                    containerLayout.addView(psicashPurchaseItemView);

                    Collections.sort(skuDetailsList, (skuDetails, t1) -> {
                        if (skuDetails.getPriceAmountMicros() > t1.getPriceAmountMicros()) {
                            return 1;
                        } else if (skuDetails.getPriceAmountMicros() < t1.getPriceAmountMicros()) {
                            return -1;
                        } else {
                            return 0;
                        }
                    });

                    for (SkuDetails skuDetails : skuDetailsList) {
                        int itemValue = 0;
                        try {
                            itemValue = GooglePlayBillingHelper.IAB_PSICASH_SKUS_TO_VALUE.get(skuDetails.getSku());
                        } catch (NullPointerException e) {
                            Utils.MyLog.g("PsiCashStoreActivity: error getting price for sku: " + skuDetails.getSku());
                        }
                        String itemTitle = NumberFormat.getInstance().format(itemValue);
                        psicashPurchaseItemView = inflater.inflate(R.layout.psicash_purchase_template, container, false);

                        ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_title)).setText(itemTitle);
                        ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_description)).setText(skuDetails.getDescription());

                        btn = psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_price);
                        btn.setText(skuDetails.getPrice());
                        btn.setOnClickListener(v -> {
                            final Activity activity = getActivity();
                            try {
                                Intent intentData = new Intent();
                                intentData.putExtra(PsiCashStoreActivity.PURCHASE_PSICASH, true);
                                intentData.putExtra(PsiCashStoreActivity.PURCHASE_PSICASH_SKU_DETAILS_JSON, skuDetails.getOriginalJson());
                                activity.setResult(Activity.RESULT_OK, intentData);
                                activity.finish();
                            } catch (NullPointerException ignored) {
                            }
                        });

                        containerLayout.addView(psicashPurchaseItemView);
                    }

                })
                .subscribe();
    }
}

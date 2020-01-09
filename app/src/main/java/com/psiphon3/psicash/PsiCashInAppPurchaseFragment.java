package com.psiphon3.psicash;

import android.app.Activity;
import android.arch.lifecycle.ViewModelProviders;
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

import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.BillingRepository;
import com.psiphon3.billing.StatusActivityBillingViewModel;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.text.NumberFormat;
import java.util.Collections;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;

public class PsiCashInAppPurchaseFragment extends Fragment {

    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private StatusActivityBillingViewModel billingViewModel;

    private Scene sceneBuyPsiCash;
    private Scene sceneUnfinishedPsiCashPurchase;

    private ViewGroup sceneRoot;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Context ctx = container.getContext();
        BillingRepository billingRepository = BillingRepository.getInstance(ctx);
        billingViewModel = ViewModelProviders.of(getActivity()).get(StatusActivityBillingViewModel.class);

        View view = inflater.inflate(R.layout.psicash_store_scene_container_fragment, container, false);

        sceneRoot = view.findViewById(R.id.scene_root);

        sceneBuyPsiCash = Scene.getSceneForLayout(sceneRoot, R.layout.buy_psicash_scene, ctx);
        sceneUnfinishedPsiCashPurchase = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_not_connected_scene, ctx);

        sceneBuyPsiCash.setEnterAction(buyPsiCashEnterAction(inflater, container, view));

        TransitionManager.go(sceneBuyPsiCash);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        billingViewModel.queryAllSkuDetails();
        billingViewModel.queryCurrentSubscriptionStatus();
    }

    private Runnable buyPsiCashEnterAction(LayoutInflater inflater, ViewGroup container, View view) {
        return () -> {
            billingViewModel.allSkuDetailsSingle()
                    .toObservable()
                    .flatMap(Observable::fromIterable)
                    .filter(skuDetails -> {
                        String sku = skuDetails.getSku();
                        return BillingRepository.IAB_PSICASH_SKUS_TO_VALUE.containsKey(sku);
                    })
                    .toList()
                    .doOnSuccess(skuDetailsList -> {
                        LinearLayout containerLayout = view.findViewById(R.id.psicash_purchase_options_container);

                        // Add "Watch ad to earn 35 PsiCash" button
                        View psicashPurchaseItemView = inflater.inflate(R.layout.psicash_purchase_template, container, false);

                        ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_title)).setText(R.string.psicash_purchase_free_name);
                        ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_description)).setText(R.string.psicash_purchase_free_description);

                        Button btn = psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_price);
                        btn.setText(R.string.psicash_purchase_free_button_price);
                        btn.setOnClickListener(v -> {
                            final Activity activity = getActivity();
                            if (activity == null) {
                                return;
                            }

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
                            if (skuDetails.getPriceAmountMicros() >= t1.getPriceAmountMicros())
                                return 1;
                            else if (skuDetails.getPriceAmountMicros() <= t1.getPriceAmountMicros())
                                return -1;
                            else return 0;
                        });

                        for (SkuDetails skuDetails : skuDetailsList) {
                            int itemValue = 0;
                            try {
                                itemValue = BillingRepository.IAB_PSICASH_SKUS_TO_VALUE.get(skuDetails.getSku());
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
                                if (activity == null) {
                                    return;
                                }

                                try {
                                    Intent intentData = new Intent();
                                    intentData.putExtra(PsiCashStoreActivity.PURCHASE_PSICASH, true);
                                    intentData.putExtra(PsiCashStoreActivity.PURCHASE_PSICASH_SKU_DETAILS_JSON, skuDetails.getOriginalJson());
                                    activity.setResult(Activity.RESULT_OK, intentData);
                                    activity.finish();
                                } catch (NullPointerException e) {
                                }
                            });

                            containerLayout.addView(psicashPurchaseItemView);
                        }

                    })
                    .subscribe();

        };
    }
}

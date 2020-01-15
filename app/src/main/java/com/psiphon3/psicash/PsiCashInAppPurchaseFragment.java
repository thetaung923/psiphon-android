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

import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.text.NumberFormat;
import java.util.Collections;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;

public class PsiCashInAppPurchaseFragment extends Fragment {
    private GooglePlayBillingHelper googlePlayBillingHelper;
    private PsiCashStoreViewModel viewModel;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    private Scene sceneBuyPsiCashFromPlayStore;
    private Scene sceneConnectToFinishPsiCashPurchase;
    private Scene sceneWaitFinishPsiCashPurchase;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        viewModel = ViewModelProviders.of(getActivity()).get(PsiCashStoreViewModel.class);

        Context ctx = getContext();
        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(ctx);

        View view = inflater.inflate(R.layout.psicash_store_scene_container_fragment, container, false);

        ViewGroup sceneRoot = view.findViewById(R.id.scene_root);
        View progressOverlay = view.findViewById(R.id.progress_overlay);

        sceneBuyPsiCashFromPlayStore = Scene.getSceneForLayout(sceneRoot, R.layout.psicash_buy_from_playstore_scene, ctx);
        sceneConnectToFinishPsiCashPurchase = Scene.getSceneForLayout(sceneRoot, R.layout.psicash_connect_to_finish_purchase_scene, ctx);
        sceneWaitFinishPsiCashPurchase = Scene.getSceneForLayout(sceneRoot, R.layout.psicash_wait_to_finish_purchase_scene, ctx);

        sceneBuyPsiCashFromPlayStore.setEnterAction(() ->
                compositeDisposable.add(googlePlayBillingHelper.allSkuDetailsSingle()
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
                        final Activity activity1 = getActivity();
                        try {
                            Intent data = new Intent();
                            data.putExtra(PsiCashStoreActivity.PURCHASE_PSICASH_GET_FREE, true);
                            activity1.setResult(Activity.RESULT_OK, data);
                            activity1.finish();
                        } catch (NullPointerException ignored) {
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
                .subscribe()));

        sceneConnectToFinishPsiCashPurchase.setEnterAction(
                () -> {
                    progressOverlay.setVisibility(View.GONE);
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
                });

        sceneWaitFinishPsiCashPurchase.setEnterAction(
                () -> progressOverlay.setVisibility(View.GONE));

        compositeDisposable.add(googlePlayBillingHelper.purchaseStateFlowable()
                .distinctUntilChanged()
                .switchMap(purchaseState -> {
                    final Purchase purchase = purchaseState.purchase();
                    if (purchase != null && GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                        return viewModel.getTunnelServiceInteractor().tunnelStateFlowable()
                                .map(tunnelState ->
                                        tunnelState.isStopped() ?
                                                SceneState.CONNECT_TO_FINISH :
                                                SceneState.WAIT_TO_FINISH);
                    } else {
                        return Flowable.just(SceneState.BUY_FROM_PLAYSTORE);
                    }
                })
                .doOnNext(sceneState -> {
                    if (sceneState == SceneState.CONNECT_TO_FINISH)
                        TransitionManager.go(sceneConnectToFinishPsiCashPurchase);
                    else if (sceneState == SceneState.WAIT_TO_FINISH) {
                        TransitionManager.go(sceneWaitFinishPsiCashPurchase);
                    } else if (sceneState == SceneState.BUY_FROM_PLAYSTORE) {
                        TransitionManager.go(sceneBuyPsiCashFromPlayStore);
                    }
                })
                .subscribe());

        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    @Override
    public void onResume() {
        super.onResume();
        googlePlayBillingHelper.queryAllSkuDetails();
        googlePlayBillingHelper.queryAllPurchases();
    }

    private enum  SceneState {
        WAIT_TO_FINISH, BUY_FROM_PLAYSTORE, CONNECT_TO_FINISH
    }
}

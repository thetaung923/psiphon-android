package com.psiphon3.psicash;

import android.app.Activity;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.transition.Scene;
import android.support.transition.TransitionManager;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.GridLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class PsiCashSpeedBoostPurchaseFragment extends Fragment {
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PsiCashStoreViewModel viewModel;

    private Scene sceneTunnelNotRunning;
    private Scene sceneTunnelConnected;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        viewModel = ViewModelProviders.of(getActivity()).get(PsiCashStoreViewModel.class);

        View view = inflater.inflate(R.layout.psicash_store_scene_container_fragment, container, false);

        ViewGroup sceneRoot = view.findViewById(R.id.scene_root);
        View progressOverlay = view.findViewById(R.id.progress_overlay);

        Context ctx = getContext();
        sceneTunnelNotRunning = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_not_running_scene, ctx);
        sceneTunnelConnected = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_connected_scene, ctx);

        sceneTunnelNotRunning.setEnterAction(() -> {
            progressOverlay.setVisibility(View.GONE);
            Button connectBtn = sceneTunnelNotRunning.getSceneRoot().findViewById(R.id.connect_psiphon_btn);
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

        sceneTunnelConnected.setEnterAction(() -> {
            AtomicBoolean progressShowing = new AtomicBoolean(progressOverlay.getVisibility() == View.VISIBLE);
            compositeDisposable.add(
                    viewModel.getPsiCashClientSingle(ctx)
                            .flatMapObservable(PsiCashClient::getPsiCashLocal)
                            .firstOrError()
                            .subscribeOn(Schedulers.computation())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(
                                    psiCash -> {
                                        if (progressShowing.compareAndSet(true, false)) {
                                            progressOverlay.setVisibility(View.GONE);
                                        }
                                        populateSpeedBoostPurchases(view, psiCash);
                                    },
                                    err -> Utils.MyLog.g("PurchaseSpeedBoostFragment: error getting local PsiCash state:" + err)
                            ));
        });

        viewModel.getTunnelServiceInteractor().tunnelStateFlowable()
                .filter(state -> !state.isUnknown())
                .distinctUntilChanged()
                .doOnNext(state -> {
                    if (state.isStopped()) {
                        TransitionManager.go(sceneTunnelNotRunning);
                    } else if (state.isRunning() && state.connectionData().isConnected()) {
                        TransitionManager.go(sceneTunnelConnected);
                    }
                })
                .subscribe();

        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    private void populateSpeedBoostPurchases(View view, PsiCashModel.PsiCash psiCash) {
        final Activity activity = getActivity();
        if (activity == null || psiCash == null) {
            return;
        }

        final int columnCount;
        int orientation = getResources().getConfiguration().orientation;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            columnCount = 5;
        } else {
            columnCount = 3;
        }

        GridLayout containerLayout = view.findViewById(R.id.purchase_speedboost_grid);
        containerLayout.setColumnCount(columnCount);

        containerLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                                       int oldTop, int oldRight, int oldBottom) {
                if (left == 0 && top == 0 && right == 0 && bottom == 0) {
                    return;
                }
                ViewGroup viewGroup = (ViewGroup) v;
                int childWidth = viewGroup.getWidth() / columnCount;
                if (childWidth == 0) {
                    return;
                }
                float ratio = 248.0f / 185.0f;
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    View child = viewGroup.getChildAt(i);
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    params.width = childWidth;
                    params.height = (int) (childWidth * ratio);
                    child.setLayoutParams(params);
                }
                v.removeOnLayoutChangeListener(this);
            }
        });

        int balanceInteger = (int) (Math.floor((long) (psiCash.balance() / 1e9)));

        TextView balanceLabel = activity.findViewById(R.id.psicash_balance_label);
        balanceLabel.setText(String.format(Locale.US, "%d", balanceInteger));

        for (PsiCashLib.PurchasePrice price : psiCash.purchasePrices()) {
            LinearLayout speedboostItemLayout = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.speedboost_button_template, null);
            RelativeLayout relativeLayout = speedboostItemLayout.findViewById(R.id.speedboost_relative_layout);

            final int priceInteger = (int) (Math.floor((long) (price.price / 1e9)));
            int drawableResId = getSpeedBoostPurchaseDrawableResId(priceInteger);
            relativeLayout.setBackgroundResource(drawableResId);

            TextView durationLabel = speedboostItemLayout.findViewById(R.id.speedboost_purchase_label);
            final String durationString = getDurationString(price.distinguisher);
            durationLabel.setText(durationString);

            Button button = speedboostItemLayout.findViewById(R.id.speedboost_purchase_button);

            Drawable buttonDrawable = activity.getResources().getDrawable(R.drawable.psicash_coin).mutate();
            buttonDrawable.setBounds(0,
                    0,
                    (int) (buttonDrawable.getIntrinsicWidth() * 0.7),
                    (int) (buttonDrawable.getIntrinsicHeight() * 0.7));

            button.setCompoundDrawables(buttonDrawable, null, null, null);

            String priceTag = String.format(Locale.US, "%d", priceInteger);
            button.setText(priceTag);

            if (balanceInteger >= priceInteger) {
                buttonDrawable.setAlpha(255);
                button.setOnClickListener(v -> {
                    String confirmationMessage = String.format(
                            activity.getString(R.string.lbl_confirm_speedboost_purchase),
                            durationString,
                            priceInteger
                    );

                    new AlertDialog.Builder(activity)
                            .setIcon(R.drawable.psicash_coin)
                            .setTitle(activity.getString(R.string.speed_boost_button_caption))
                            .setMessage(confirmationMessage)
                            .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                            })
                            .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                                try {
                                    Intent data = new Intent();
                                    data.putExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST, true);
                                    data.putExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_DISTINGUISHER, price.distinguisher);
                                    data.putExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_EXPECTED_PRICE, price.price);
                                    activity.setResult(Activity.RESULT_OK, data);
                                    activity.finish();
                                } catch (NullPointerException ignored) {
                                }
                            })
                            .setCancelable(true)
                            .create()
                            .show();
                });
            } else {
                buttonDrawable.setAlpha(127);
                button.setCompoundDrawables(buttonDrawable, null, null, null);
                button.setEnabled(false);
            }

            containerLayout.addView(speedboostItemLayout);
        }
    }

    private String getDurationString(String distinguisher) {
        // TODO: return a translated string
        return distinguisher;
    }

    private int getSpeedBoostPurchaseDrawableResId(int priceValue) {
        int[] backgrounds = {
                R.drawable.speedboost_background_orange,
                R.drawable.speedboost_background_pink,
                R.drawable.speedboost_background_purple,
                R.drawable.speedboost_background_blue,
                R.drawable.speedboost_background_light_blue,
                R.drawable.speedboost_background_mint,
                R.drawable.speedboost_background_orange_2,
                R.drawable.speedboost_background_yellow,
                R.drawable.speedboost_background_fluoro_green,
        };
        int index = ((priceValue / 100) - 1) % backgrounds.length;
        return backgrounds[index];
    }
}

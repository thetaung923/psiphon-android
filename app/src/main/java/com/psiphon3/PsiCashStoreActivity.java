package com.psiphon3;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.transition.Scene;
import android.support.transition.TransitionManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.GridLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.BillingRepository;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import org.json.JSONException;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class PsiCashStoreActivity extends LocalizedActivities.AppCompatActivity {

    public static final String PSICASH_BALANCE_EXTRA = "PSICASH_BALANCE_EXTRA";
    public static final String PSICASH_SKU_DETAILS_LIST_EXTRA = "PSICASH_SKU_DETAILS_LIST_EXTRA";
    static final String PURCHASE_SPEEDBOOST = "PURCHASE_SPEEDBOOST";
    static final String PURCHASE_SPEEDBOOST_DISTINGUISHER = "PURCHASE_SPEEDBOOST_DISTINGUISHER";
    static final String PURCHASE_SPEEDBOOST_EXPECTED_PRICE = "PURCHASE_SPEEDBOOST_EXPECTED_PRICE";
    static final String PURCHASE_PSICASH = "PURCHASE_PSICASH";
    static final String PURCHASE_PSICASH_SKU_JSON = "PURCHASE_PSICASH_SKU_JSON";
    public static final String SPEEDBOOST_CONNECT_PSIPHON_EXTRA = "SPEEDBOOST_CONNECT_PSIPHON_EXTRA";


    TabLayout tabLayout;
    ViewPager viewPager;
    PageAdapter pageAdapter;

    static Single<PsiCashClient> getPsiCashClientSingle(final Context context) {
        return Single.fromCallable(() -> PsiCashClient.getInstance(context));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.psicash_store_activity);

        TextView balanceLabel = findViewById(R.id.psicash_balance_label);
        int uiBalance = getIntent().getIntExtra(PSICASH_BALANCE_EXTRA, 0);
        balanceLabel.setText(String.format(Locale.US, "%d", uiBalance));

        tabLayout = findViewById(R.id.psicash_store_tablayout);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        ArrayList<String> jsonSkuDetailsList = getIntent().getStringArrayListExtra(PSICASH_SKU_DETAILS_LIST_EXTRA);
        pageAdapter = new PageAdapter(getSupportFragmentManager(), tabLayout.getTabCount(), jsonSkuDetailsList);
        viewPager = findViewById(R.id.psicash_store_viewpager);
        viewPager.setAdapter(pageAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
    }

    static class PageAdapter extends FragmentPagerAdapter {
        private final ArrayList<String> jsonSkuDetailsList;
        private int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs, ArrayList<String> jsonSkuDetailsList) {
            super(fm);
            this.numOfTabs = numOfTabs;
            this.jsonSkuDetailsList = jsonSkuDetailsList;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    PurchasePsiCashFragment purchasePsiCashFragment = new PurchasePsiCashFragment();
                    Bundle data = new Bundle();
                    data.putStringArrayList(PSICASH_SKU_DETAILS_LIST_EXTRA, jsonSkuDetailsList);
                    purchasePsiCashFragment.setArguments(data);
                    return purchasePsiCashFragment;
                case 1:
                    return new PurchaseSpeedBoostFragment();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return numOfTabs;
        }
    }

    public static class PurchaseSpeedBoostFragment extends Fragment {

        private CompositeDisposable compositeDisposable = new CompositeDisposable();
        private TunnelServiceInteractor tunnelServiceInteractor;

        private Scene sceneTunnelNotRunning;
        private Scene sceneTunnelNotConnected;
        private Scene sceneTunnelConnected;

        private ViewGroup sceneRoot;

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.purchase_speedboost_fragment, container, false);

            sceneRoot = (ViewGroup) view.findViewById(R.id.scene_root);

            Context ctx = container.getContext();
            sceneTunnelNotRunning = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_not_running_scene, ctx);
            sceneTunnelNotConnected = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_not_connected_scene, ctx);
            sceneTunnelConnected = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_connected_scene, ctx);

            sceneTunnelNotRunning.setEnterAction(() -> {
                Button connectBtn = view.findViewById(R.id.connect_psiphon_btn);
                connectBtn.setOnClickListener(v -> {
                    final Activity activity = getActivity();
                    if (activity == null) {
                        return;
                    }
                    try {
                        Intent data = new Intent();
                        data.putExtra(SPEEDBOOST_CONNECT_PSIPHON_EXTRA, true);
                        activity.setResult(RESULT_OK, data);
                        activity.finish();
                    } catch (NullPointerException e) {
                    }
                });
            });


            sceneTunnelConnected.setEnterAction(() -> {
                compositeDisposable.add(
                        getPsiCashClientSingle(ctx)
                                .flatMapObservable(PsiCashClient::getPsiCashLocal)
                                .subscribeOn(Schedulers.computation())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(
                                        psiCash -> populateSpeedboostPurchasesScreen(view, psiCash),
                                        err -> Utils.MyLog.g("PurchaseSpeedBoostFragment: error getting local PsiCash state:" + err)
                                ));
            });

            tunnelServiceInteractor = new TunnelServiceInteractor(ctx);
            tunnelServiceInteractor.tunnelStateFlowable()
                    .filter(state -> !state.isUnknown())
                    .distinctUntilChanged()
                    .doOnNext(state -> {
                        if (!state.isRunning()) {
                            TransitionManager.go(sceneTunnelNotRunning);
                        } else if (!state.connectionData().isConnected()) {
                            TransitionManager.go(sceneTunnelNotConnected);
                        } else if (state.connectionData().isConnected()) {
                            TransitionManager.go(sceneTunnelConnected);
                        }
                    })
                    .subscribe();

            return view;
        }

        @Override
        public void onResume() {
            super.onResume();
            tunnelServiceInteractor.resume(getActivity().getApplicationContext());
        }

        @Override
        public void onPause() {
            super.onPause();
            tunnelServiceInteractor.pause(getActivity().getApplicationContext());
        }

        private void populateSpeedboostPurchasesScreen(View view, PsiCashModel.PsiCash psiCash) {
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
            containerLayout.post(() -> {
                for (int i = 0; i < containerLayout.getChildCount(); i++) {
                    View child = containerLayout.getChildAt(i);
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    params.width = containerLayout.getWidth() / columnCount;
                    params.height = params.width * 248 / 185;
                    child.setLayoutParams(params);
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
                                        data.putExtra(PURCHASE_SPEEDBOOST, true);
                                        data.putExtra(PURCHASE_SPEEDBOOST_DISTINGUISHER, price.distinguisher);
                                        data.putExtra(PURCHASE_SPEEDBOOST_EXPECTED_PRICE, price.price);
                                        activity.setResult(RESULT_OK, data);

                                        activity.finish();
                                    } catch (NullPointerException e) {
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

    public static class PurchasePsiCashFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.purchase_psicash_fragment, container, false);

            LinearLayout containerLayout = view.findViewById(R.id.psicash_purchase_options_container);

            // Add "Watch ad to earn 35 PsiCash" button
            View psicashPurchaseItemView = inflater.inflate(R.layout.psicash_purchase_template, container, false);

            ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_title)).setText(R.string.psicash_purchase_free_name);
            ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_description)).setText(R.string.psicash_purchase_free_description);
            ((Button) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_price)).setText(R.string.psicash_purchase_free_button_price);

            containerLayout.addView(psicashPurchaseItemView);

            List<SkuDetails> skuDetailsList = new ArrayList<>();
            Bundle data = getArguments();
            if (data != null) {
                List<String> jsonSkuDetailsList = data.getStringArrayList(PSICASH_SKU_DETAILS_LIST_EXTRA);
                jsonSkuDetailsList = jsonSkuDetailsList == null ? Collections.emptyList() : jsonSkuDetailsList;
                for (String jsonSkuDetails : jsonSkuDetailsList) {
                    SkuDetails skuDetails;
                    try {
                        skuDetails = new SkuDetails(jsonSkuDetails);
                        skuDetailsList.add(skuDetails);
                    } catch (JSONException e) {
                        Utils.MyLog.g("PsiCashStoreActivity: error parsing SkuDetails: " + e);
                    }
                }
            }

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
                ((Button) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_price)).setText(skuDetails.getPrice());
                containerLayout.addView(psicashPurchaseItemView);
            }

            return view;
        }
    }
}

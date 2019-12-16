package com.psiphon3;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.transition.Scene;
import android.support.transition.TransitionManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.util.ArrayList;
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
    static final String PURCHASE_SPEEDBOOST_OPTION = "PURCHASE_SPEEDBOOST_OPTION";
    static final String PURCHASE_PSICASH = "PURCHASE_PSICASH";
    static final String PURCHASE_PSICASH_SKU_JSON = "PURCHASE_PSICASH_SKU_JSON";

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

            sceneTunnelNotRunning = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_not_running_scene, getActivity());
            sceneTunnelNotConnected = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_not_connected_scene, getActivity());
            sceneTunnelConnected = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_connected_scene, getActivity());

            sceneTunnelConnected.setEnterAction(() -> {
                compositeDisposable.add(
                        getPsiCashClientSingle(getActivity().getApplicationContext())
                                .flatMapObservable(PsiCashClient::getPsiCashLocal)
                                .subscribeOn(Schedulers.computation())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(
                                        psiCashModel -> populatePurchaseOptions(view, psiCashModel.purchasePrices()),
                                        err -> {
                                            Utils.MyLog.g("PurchaseSpeedBoostFragment: error getting local PsiCash state:" + err);
                                        }
                                ));
            });

            tunnelServiceInteractor = new TunnelServiceInteractor(getActivity().getApplicationContext());
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

        private void populatePurchaseOptions(View view, List<PsiCashLib.PurchasePrice> purchasePrices) {
            /*
            FlexboxLayout flexboxLayout = view.findViewById(R.id.purchase_speedboost_flexbox);
            flexboxLayout.setFlexDirection(FlexDirection.ROW);

            for (PsiCashLib.PurchasePrice price : purchasePrices) {
                Drawable drawable = ContextCompat.getDrawable(getActivity().getApplicationContext(), R.drawable.speedboost_background_blue);
                FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ImageView myImage = new ImageView(getActivity());
                myImage.setImageDrawable(drawable);
//                lp.setFlexBasisPercent(0.33f);
                myImage.setLayoutParams(lp);
                flexboxLayout.addView(myImage);
/*

                Drawable drawable = ContextCompat.getDrawable(getActivity().getApplicationContext(), R.drawable.speedboost_background_blue);
                FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());

                TextView textView = new TextView(getActivity());
                textView.setBackgroundDrawable(drawable);
                textView.setText("Price: " + (int)(price.price/1e9));
                textView.setLayoutParams(lp);
                flexboxLayout.addView(textView);
            }
        }



/*
                Button button = view.findViewById(R.id.speedboost_purchase_button);
                button.setOnClickListener(v -> {
                    try {
                        Intent data = new Intent();
                        data.putExtra(PURCHASE_SPEEDBOOST, true);
                        data.putExtra(PURCHASE_SPEEDBOOST_OPTION, "1hr");
                        getActivity().setResult(RESULT_OK, data);

                        getActivity().finish();
                    } catch (NullPointerException e) {

                    }
                });

 */
        }
    }

    public static class PurchasePsiCashFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.purchase_psicash_fragment, container, false);

            ArrayList<String> jsonSkuDetails = getArguments().getStringArrayList(PSICASH_SKU_DETAILS_LIST_EXTRA);

            return view;
        }
    }
}

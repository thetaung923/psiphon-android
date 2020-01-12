package com.psiphon3.psicash;

import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.View;
import android.widget.TextView;

import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class PsiCashStoreActivity extends LocalizedActivities.AppCompatActivity {
    public static final String PURCHASE_SPEEDBOOST = "PURCHASE_SPEEDBOOST";
    static final String PURCHASE_SPEEDBOOST_DISTINGUISHER = "PURCHASE_SPEEDBOOST_DISTINGUISHER";
    static final String PURCHASE_SPEEDBOOST_EXPECTED_PRICE = "PURCHASE_SPEEDBOOST_EXPECTED_PRICE";
    public static final String PURCHASE_PSICASH = "PURCHASE_PSICASH";
    static final String PURCHASE_PSICASH_GET_FREE = "PURCHASE_PSICASH_GET_FREE";
    public static final String PURCHASE_PSICASH_SKU_DETAILS_JSON = "PURCHASE_PSICASH_SKU_DETAILS_JSON";
    public static final String SPEEDBOOST_CONNECT_PSIPHON_EXTRA = "SPEEDBOOST_CONNECT_PSIPHON_EXTRA";

    private PsiCashStoreViewModel viewModel;
    private View psicashStoreMainView;
    private View psicashNotAvailableView;
    private View progressOverlay;


    private TabLayout tabLayout;
    private ViewPager viewPager;
    private PageAdapter pageAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.psicash_store_activity);

        psicashStoreMainView = findViewById(R.id.psicash_store_main);
        psicashNotAvailableView = findViewById(R.id.psicash_store_not_available);
        progressOverlay = findViewById(R.id.progress_overlay);

        viewModel = ViewModelProviders.of(this).get(PsiCashStoreViewModel.class);

        TextView balanceLabel = findViewById(R.id.psicash_balance_label);
        viewModel.getPsiCashClientSingle(getApplicationContext())
                .flatMapObservable(PsiCashClient::getPsiCashLocal)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .firstOrError()
                .map(model -> {
                    long balance = model.balance();
                    long reward = model.reward();
                    return (int) (Math.floor((long) ((reward * 1e9 + balance) / 1e9)));
                })
                .doOnSuccess(uiBalance ->
                        balanceLabel.setText(String.format(Locale.US, "%d", uiBalance)))
                .subscribe();

        AtomicBoolean progressShowing = new AtomicBoolean(progressOverlay.getVisibility() == View.VISIBLE);
        viewModel.getTunnelServiceInteractor().tunnelStateFlowable()
                .filter(state -> !state.isUnknown())
                .distinctUntilChanged()
                .doOnNext(state -> {
                    if (progressShowing.compareAndSet(true, false)) {
                        progressOverlay.setVisibility(View.GONE);
                    }
                    if (state.isRunning() && !state.connectionData().isConnected()) {
                        psicashNotAvailableView.setVisibility(View.VISIBLE);
                        psicashStoreMainView.setVisibility(View.GONE);
                    } else {
                        psicashNotAvailableView.setVisibility(View.GONE);
                        psicashStoreMainView.setVisibility(View.VISIBLE);
                    }
                })
                .subscribe();

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

        pageAdapter = new PageAdapter(getSupportFragmentManager(), tabLayout.getTabCount());
        viewPager = findViewById(R.id.psicash_store_viewpager);
        viewPager.setAdapter(pageAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.getTunnelServiceInteractor().resume(getApplicationContext());
    }

    @Override
    protected void onPause() {
        super.onPause();
        viewModel.getTunnelServiceInteractor().pause(getApplicationContext());
    }

    static class PageAdapter extends FragmentPagerAdapter {
        private int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs) {
            super(fm);
            this.numOfTabs = numOfTabs;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new PsiCashInAppPurchaseFragment();
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

}

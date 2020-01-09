/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.design.widget.SwipeDismissBehavior;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewPropertyAnimator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.billingclient.api.SkuDetails;
import com.jakewharton.rxbinding2.view.RxView;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.PsiCashIntent;
import com.psiphon3.psicash.PsiCashListener;
import com.psiphon3.psicash.PsiCashStoreActivity;
import com.psiphon3.psicash.PsiCashViewModel;
import com.psiphon3.psicash.PsiCashViewModelFactory;
import com.psiphon3.psicash.PsiCashViewState;
import com.psiphon3.psicash.RewardedVideoClient;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashFragment extends Fragment implements MviView<PsiCashIntent, PsiCashViewState> {
    private static final String TAG = "PsiCashFragment";
    static final int PSICASH_DETAILS_ACTIVITY_RESULT = 20002;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable viewStatesDisposable;
    private PsiCashViewModel psiCashViewModel;
    private GooglePlayBillingHelper googlePlayBillingHelper;


    private Relay<PsiCashIntent> intentsPublishRelay = PublishRelay.<PsiCashIntent>create().toSerialized();
    private Relay<TunnelState> tunnelConnectionStateBehaviorRelay = BehaviorRelay.<TunnelState>create().toSerialized();
    private Relay<LifeCycleEvent> lifecyclePublishRelay = PublishRelay.<LifeCycleEvent>create().toSerialized();
    private PublishRelay<Observable<ViewPropertyAnimator>> balanceDeltaAnimationRelay = PublishRelay.create();
    private PublishRelay<Observable<ValueAnimator>> balanceLabelAnimationRelay = PublishRelay.create();

    private TextView balanceLabel;
    private Button speedBoostBtn;
    private CountDownTimer countDownTimer;
    private View progressOverlay;
    private int currentUiBalance;
    private boolean animateOnBalanceChange = false;

    private final AtomicBoolean shouldGetPsiCashRemote = new AtomicBoolean(false);
    private boolean shouldAutoPlayVideo;
    private ActiveSpeedBoostListener activeSpeedBoostListener;

    private enum LifeCycleEvent {ON_RESUME, ON_PAUSE, ON_STOP, ON_START}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.psicash_fragment, container, false);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == PSICASH_DETAILS_ACTIVITY_RESULT) {
            if(resultCode != LocalizedActivities.Activity.RESULT_OK) {
                return;
            }
            if (data.hasExtra(PsiCashStoreActivity.PURCHASE_PSICASH)) {
                String skuString = data.getStringExtra(PsiCashStoreActivity.PURCHASE_PSICASH_SKU_DETAILS_JSON);
                try {
                    if (TextUtils.isEmpty(skuString)) {
                        throw new IllegalArgumentException("SKU is empty.");
                    }
                    SkuDetails skuDetails = new SkuDetails(skuString);
                    googlePlayBillingHelper.launchFlow(getActivity(), skuDetails).subscribe();
                } catch (JSONException | IllegalArgumentException e) {
                    Utils.MyLog.g("PsiCashFragment::onActivityResult purchase SKU error: " + e);
                }

            } else if (data.hasExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST)) {
                // TODO: implement this
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }

        Log.d(TAG, "onActivityResult:" + requestCode + ", " + resultCode + ", " + data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PsiCashListener psiCashListener = new PsiCashListener() {
            @Override
            public void onNewExpiringPurchase(Context context, PsiCashLib.Purchase purchase) {
                // Store authorization from the purchase
                Utils.MyLog.g("PsiCash::onNewExpiringPurchase: storing new authorization of accessType " + purchase.authorization.accessType);
                Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization.encoded);
                Authorization.storeAuthorization(context, authorization);

                // Send broadcast to restart the tunnel
                Utils.MyLog.g("PsiCash::onNewExpiringPurchase: send tunnel restart broadcast");
                android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
                LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
            }

            @Override
            public void onNewReward(Context context, long reward) {
                try {
                    // Store the reward amount
                    PsiCashClient.getInstance(context).putVideoReward(reward);
                    // reload local PsiCash to update the view with the new reward amount
                    intentsPublishRelay.accept(PsiCashIntent.GetPsiCashLocal.create());
                } catch (PsiCashException e) {
                    Utils.MyLog.g("PsiCash::onNewReward: failed to store video reward: " + e);
                }
            }
        };
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        RewardedVideoClient.getInstance().initWithActivity(getActivity());

        PsiCashListener psiCashListener = new PsiCashListener() {
            @Override
            public void onNewExpiringPurchase(Context context, PsiCashLib.Purchase purchase) {
                // Store authorization from the purchase
                Utils.MyLog.g("PsiCash::onNewExpiringPurchase: storing new authorization of accessType " + purchase.authorization.accessType);
                Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization.encoded);
                Authorization.storeAuthorization(context, authorization);

                // Send broadcast to restart the tunnel
                Utils.MyLog.g("PsiCash::onNewExpiringPurchase: send tunnel restart broadcast");
                android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
                LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
            }

            @Override
            public void onNewReward(Context context, long reward) {
                try {
                    // Store the reward amount
                    PsiCashClient.getInstance(context).putVideoReward(reward);
                    // reload local PsiCash to update the view with the new reward amount
                    intentsPublishRelay.accept(PsiCashIntent.GetPsiCashLocal.create());
                } catch (PsiCashException e) {
                    Utils.MyLog.g("PsiCash::onNewReward: failed to store video reward: " + e);
                }
            }
        };
        psiCashViewModel = ViewModelProviders.of(this, new PsiCashViewModelFactory(getActivity().getApplication(), psiCashListener))
                .get(PsiCashViewModel.class);

        // Pass the UI's intents to the view model
        psiCashViewModel.processIntents(intents());

        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(getActivity().getApplicationContext());

        progressOverlay = getActivity().findViewById(R.id.progress_overlay);
        speedBoostBtn = getActivity().findViewById(R.id.purchase_speedboost_btn);
        balanceLabel = getActivity().findViewById(R.id.psicash_balance_label);
        balanceLabel.setText("0");
/*
        // Buy speed boost button events.
        compositeDisposable.add(speedBoostClicksDisposable());
*/
        // Unconditionally get latest local PsiCash state when app is foregrounded
        compositeDisposable.add(getPsiCashLocalDisposable());
        // Get PsiCash tokens when tunnel connects if there are none
        compositeDisposable.add(getPsiCashTokensDisposable());
        // check if there are purchases to be removed when app is foregrounded
        compositeDisposable.add(removePurchasesDisposable());
        // try and get PsiCash state from remote on next foreground
        // if a home page had been opened and tunnel is up
        compositeDisposable.add(getOpenedHomePageRewardDisposable());

        // Floating balance delta animations, executed sequentially
        compositeDisposable.add(balanceDeltaAnimationRelay.concatMap(s -> s)
                .subscribe(ViewPropertyAnimator::start, err -> {
                    Utils.MyLog.g("Floating balance delta animation error: " + err);
                }));

        // Balance label increase animations, executed sequentially
        compositeDisposable.add(balanceLabelAnimationRelay.concatMap(s -> s)
                .subscribe(ValueAnimator::start, err -> {
                    Utils.MyLog.g("Balance label increase animation error: " + err);
                }));
    }


    public void onPsiCashStoreClick(int uiBalance) {
        compositeDisposable.add(
                googlePlayBillingHelper.subscriptionStateFlowable()
                        .firstOrError()
                        .flatMap(subscriptionState -> {
                            if (subscriptionState.hasValidPurchase()) {
                                return Single.just(false);
                            }
                            return Single.just(true);
                        })
                        .doOnSuccess(shouldOpenPsiCashStore -> {
                            if (shouldOpenPsiCashStore) {
                                Intent intent = new Intent(getActivity(), PsiCashStoreActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                intent.putExtra(PsiCashStoreActivity.PSICASH_BALANCE_EXTRA, uiBalance);
                                getActivity().startActivityForResult(intent, PSICASH_DETAILS_ACTIVITY_RESULT);
                            }
                        })
                        .subscribe()
        );
    }

    private Disposable removePurchasesDisposable() {
        return lifeCycleEventsObservable()
                .filter(s -> s == LifeCycleEvent.ON_RESUME)
                .doOnNext(__ -> {
                    // Check if there are possibly expired purchases to remove in case activity
                    // was in the background when MSG_AUTHORIZATIONS_REMOVED was sent by the service
                    final AppPreferences mp = new AppPreferences(getContext());
                    if (mp.getBoolean(this.getString(R.string.persistentAuthorizationsRemovedFlag), false)) {
                        mp.put(this.getString(R.string.persistentAuthorizationsRemovedFlag), false);
                        removePurchases(getContext());
                    }
                })
                .subscribe();
    }

    @Override
    public void onPause() {
        super.onPause();

        shouldAutoPlayVideo = false;

        unbindViewState();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_PAUSE);
    }

    @Override
    public void onResume() {
        super.onResume();
        bindViewState();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_RESUME);
        googlePlayBillingHelper.queryCurrentSubscriptionStatus();
        googlePlayBillingHelper.queryAllSkuDetails();
    }

    @Override
    public void onStop() {
        super.onStop();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_STOP);
    }

    @Override
    public void onStart() {
        super.onStart();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_START);
    }

    private void bindViewState() {
        // Subscribe to the RewardedVideoViewModel and render every emitted state
        viewStatesDisposable = viewStatesDisposable();
    }

    private void unbindViewState() {
        if (viewStatesDisposable != null) {
            viewStatesDisposable.dispose();
        }
    }

    private Disposable viewStatesDisposable() {
        // Do not render PsiCash view states if user is subscribed
        return subscriptionStateObservable()
                .flatMap(s -> s.hasValidPurchase() ?
                        Observable.empty() :
                        psiCashViewModel.states())
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render);
    }

    private Disposable speedBoostClicksDisposable() {
        return RxView.clicks(speedBoostBtn)
                .debounce(200, TimeUnit.MILLISECONDS)
                .withLatestFrom(tunnelConnectionStateObservable(), (__, state) -> {
                    boolean hasActiveSpeedBoost = (boolean) speedBoostBtn.getTag(R.id.hasActiveSpeedBoostTag);
                    if (hasActiveSpeedBoost) {
                        if(state.isRunning() && state.connectionData().isConnected()) {
                            return PsiCashIntent.GetPsiCashRemote.create(state);
                        } else {
                            return PsiCashIntent.GetPsiCashLocal.create();
                        }
                    } else {
                        return PsiCashIntent.PurchaseSpeedBoost.create(state,
                                (PsiCashLib.PurchasePrice) speedBoostBtn.getTag(R.id.speedBoostPrice));
                    }
                })
                .subscribe(intentsPublishRelay);
    }

    private Disposable getPsiCashLocalDisposable() {
        return lifeCycleEventsObservable()
                .filter(s -> s == LifeCycleEvent.ON_RESUME)
                .map(__ -> PsiCashIntent.GetPsiCashLocal.create())
                .subscribe(intentsPublishRelay);
    }

    private Disposable getPsiCashTokensDisposable() {
        // If PsiCash doesn't have valid tokens get them from the server once the tunnel is connected
        return tunnelConnectionStateObservable()
                .filter(state -> state.isRunning() && state.connectionData().isConnected())
                .takeWhile(__ -> {
                    try {
                        if (!PsiCashClient.getInstance(getContext()).hasValidTokens()) {
                            return true;
                        }
                    } catch (PsiCashException e) {
                        // Complete stream if error
                        Utils.MyLog.g("PsiCashClient::hasValidTokens error: " + e);
                        return false;
                    }
                    return false;
                })
                .map(PsiCashIntent.GetPsiCashRemote::create)
                .subscribe(intentsPublishRelay);
    }

    private Disposable getOpenedHomePageRewardDisposable() {
        // If we detect that a home page has been opened we will try to get latest PsiCash state
        // from the PsiCash server but only if tunnel is up an running.
        return lifeCycleEventsObservable()
                .filter(s -> s == LifeCycleEvent.ON_START && shouldGetPsiCashRemote.getAndSet(false))
                .switchMapSingle(__ -> tunnelConnectionStateObservable()
                        .filter(state -> state.isRunning() && state.connectionData().isConnected())
                        .firstOrError()
                        .map(PsiCashIntent.GetPsiCashRemote::create))
                .subscribe(intentsPublishRelay);
    }

    private boolean hasValidTokens() {
        try {
            return PsiCashClient.getInstance(getContext()).hasValidTokens();
        } catch (PsiCashException e) {
            Utils.MyLog.g("PsiCash::hasValidTokens() error: " + e);
            return false;
        }
    }

    public void removePurchases(Context ctx) {
        List<String> purchasesToRemove = new ArrayList<>();
        // remove purchases that are not in the authorizations database.
        try {
            // get all persisted authorizations as base64 encoded strings
            List<String> encodedAuthorizations = new ArrayList<>();
            for (Authorization a : Authorization.geAllPersistedAuthorizations(ctx)) {
                encodedAuthorizations.add(a.base64EncodedAuthorization());
            }
            List<PsiCashLib.Purchase> purchases = PsiCashClient.getInstance(ctx).getPurchases();
            if (purchases.size() == 0) {
                return;
            }
            // build a list of purchases to remove by cross referencing
            // each purchase authorization against the authorization strings list
            for (PsiCashLib.Purchase purchase : purchases) {
                if (!encodedAuthorizations.contains(purchase.authorization.encoded)) {
                    purchasesToRemove.add(purchase.id);
                    Utils.MyLog.g("PsiCash: will remove purchase of transactionClass: " + purchase.transactionClass);
                }
            }
            if (purchasesToRemove.size() > 0) {
                intentsPublishRelay.accept(PsiCashIntent.RemovePurchases.create(purchasesToRemove));
            }
        } catch (PsiCashException e) {
            Utils.MyLog.g("PsiCash: error removing expired purchases: " + e);
        }
    }

    private Observable<TunnelState> tunnelConnectionStateObservable() {
        return tunnelConnectionStateBehaviorRelay
                .hide()
                .distinctUntilChanged();
    }

    private Observable<LifeCycleEvent> lifeCycleEventsObservable() {
        return lifecyclePublishRelay
                .hide()
                .distinctUntilChanged();
    }

    private Observable<SubscriptionState> subscriptionStateObservable() {
        return googlePlayBillingHelper.subscriptionStateFlowable()
                .toObservable()
                .hide()
                .distinctUntilChanged();
    }

    @Override
    public Observable<PsiCashIntent> intents() {
        return intentsPublishRelay.hide();
    }

    @Override
    public void render(PsiCashViewState state) {
        Throwable psiCashStateError = state.error();
        if(psiCashStateError == null) {
            updateUiBalanceLabel(state);
            updateSpeedBoostButton(state);
            updateUiProgressView(state);
        } else {
            updateUiPsiCashError(psiCashStateError);
        }
    }

    private void updateUiPsiCashError(Throwable error) {

        // Clear view state error immediately.
        intentsPublishRelay.accept(PsiCashIntent.ClearErrorState.create());

        String errorMessage;
        if (error instanceof PsiCashException) {
            PsiCashException e = (PsiCashException) error;
            errorMessage = e.getUIMessage(getActivity());
        } else {
            Utils.MyLog.g("Unexpected PsiCash error: " + error.toString());
            errorMessage = getString(R.string.unexpected_error_occured_send_feedback_message);
        }

        // Custom snackbar dismiss timeout in milliseconds.
        int snackBarTimeousMs = 4000;
        Snackbar snackbar = Snackbar.make(getActivity().findViewById(R.id.psicash_coordinator_layout), errorMessage, snackBarTimeousMs);

        // Center the message in the text view.
        TextView tv = (TextView) snackbar.getView().findViewById(android.support.design.R.id.snackbar_text);
        tv.setMaxLines(5);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        } else {
            tv.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        // Add 'Ok' dismiss action button.
        snackbar.setAction(R.string.psicash_snackbar_action_ok, (View.OnClickListener) view -> {});

        // Add swipe dismiss behaviour.
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onShown(Snackbar sb) {
                super.onShown(sb);
                View snackBarView = sb.getView();
                final ViewGroup.LayoutParams lp = snackBarView.getLayoutParams();
                if (lp instanceof CoordinatorLayout.LayoutParams) {
                    final CoordinatorLayout.LayoutParams layoutParams = (CoordinatorLayout.LayoutParams) lp;
                    CoordinatorLayout.Behavior behavior = layoutParams.getBehavior();
                    if(behavior instanceof SwipeDismissBehavior){
                        ((SwipeDismissBehavior) behavior).setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_ANY);
                    }
                    layoutParams.setBehavior(behavior);
                }
            }
        });

        snackbar.show();
    }

    private void updateUiProgressView(PsiCashViewState state) {
        if (state.psiCashTransactionInFlight() || state.videoIsLoading()) {
            Utils.animateView(progressOverlay, View.VISIBLE, 0.7f, 200);
        } else {
            Utils.animateView(progressOverlay, View.GONE, 0f, 200);
        }
    }

    private void updateSpeedBoostButton(PsiCashViewState state) {
        speedBoostBtn.setEnabled(!state.psiCashTransactionInFlight());
        Date nextPurchaseExpiryDate = state.nextPurchaseExpiryDate();
        if (nextPurchaseExpiryDate != null && new Date().before(nextPurchaseExpiryDate)) {
            if(activeSpeedBoostListener != null ) {
                activeSpeedBoostListener.onActiveSpeedBoost(Boolean.TRUE);
            }
            long millisDiff = nextPurchaseExpiryDate.getTime() - new Date().getTime();
            startActiveSpeedBoostCountDown(millisDiff);
            speedBoostBtn.setTag(R.id.hasActiveSpeedBoostTag, true);
        } else {
            if(activeSpeedBoostListener != null ) {
                activeSpeedBoostListener.onActiveSpeedBoost(Boolean.FALSE);
            }
            speedBoostBtn.setTag(R.id.hasActiveSpeedBoostTag, false);
            speedBoostBtn.setText(R.string.speed_boost_button_caption);
            speedBoostBtn.setOnClickListener(v -> onPsiCashStoreClick(state.uiBalance()));
        }
    }

    private void updateUiBalanceLabel(PsiCashViewState state) {
        if(!animateOnBalanceChange) {
            animateOnBalanceChange = state.animateOnNextBalanceChange();
            balanceLabel.setText(String.format(Locale.US, "%d", state.uiBalance()));
            currentUiBalance = state.uiBalance();
            return;
        }

        int balanceDelta = state.uiBalance() - currentUiBalance;
        // Nothing changed, bail.
        if (balanceDelta == 0) {
            return;
        }

        Observable<ValueAnimator> balanceLabelAnimationObservable =
                balanceLabelAnimationObservable(currentUiBalance, state.uiBalance());
        balanceLabelAnimationRelay.accept(balanceLabelAnimationObservable);

        // Update view's current balance value.
        currentUiBalance = state.uiBalance();

        Observable<ViewPropertyAnimator> balanceDeltaAnimationObservable = balanceDeltaAnimationObservable(balanceDelta);
        balanceDeltaAnimationRelay.accept(balanceDeltaAnimationObservable);
    }

    private Observable<ValueAnimator> balanceLabelAnimationObservable(int fromVal, int toVal) {
        return Observable.create(emitter -> {
            ValueAnimator valueAnimator = ValueAnimator.ofInt(fromVal, toVal);
            valueAnimator.setDuration(1000);
            valueAnimator.addUpdateListener(valueAnimator1 ->
                    balanceLabel.setText(valueAnimator1.getAnimatedValue().toString()));
            valueAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    super.onAnimationCancel(animation);
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }
            });

            if (!emitter.isDisposed()) {
                emitter.onNext(valueAnimator);
            }
        });
    }

    private Observable<ViewPropertyAnimator> balanceDeltaAnimationObservable(int balanceDelta) {
        return Observable.create(emitter -> {
            // Floating balance delta animation
            //
            // Create a frame layout which will hold the animated balance delta text view.
            FrameLayout animatedBalanceDeltaFrameLayout = new FrameLayout(getContext());

            // Get tab layout dimensions and left/right padding, we are assuming right padding == left padding.
            LinearLayout psicashTabLayout = getActivity().findViewById(R.id.psicash_tab_layout_id);
            int paddingLeft = psicashTabLayout.getPaddingLeft();

            // Calculate vertical translation of the animated view.
            float translationY = (float)psicashTabLayout.getHeight();

            TextView floatingDeltaTextView = new TextView(getContext());
            floatingDeltaTextView.setPadding(paddingLeft, 0, paddingLeft, 0);

            // Attach text view at the bottom left(right for RTL) of the frame layout.
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            flp.gravity = Gravity.BOTTOM;
            animatedBalanceDeltaFrameLayout.addView(floatingDeltaTextView, 0, flp);

            // Set text size and calculate approximate translation offset based on the text size.
            floatingDeltaTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);

            // Add '+' sign if positive balance.
            floatingDeltaTextView.setText(String.format(Locale.US, "%s%d", balanceDelta > 0 ? "+" : "", balanceDelta));

            // Add the frame containing the balance delta text.
            final RelativeLayout.LayoutParams rlp = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT);
            rlp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
            rlp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);

            ViewGroup viewGroup = getActivity().findViewById(R.id.psicash_tab_wrapper_layout_id);

            // HACK: RTL bug workaround, see
            // https://stackoverflow.com/questions/29888439/android-rtl-layout-direction-align-center-issue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                viewGroup.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            }
            viewGroup.addView(animatedBalanceDeltaFrameLayout, 0, rlp);

            // Make sure the animated view won't get clipped as it moves across other views' boundaries.
            setAllParentsClip(animatedBalanceDeltaFrameLayout, false);

            // Bring the view to front.
            viewGroup.bringChildToFront(animatedBalanceDeltaFrameLayout);
            viewGroup.invalidate();

            // View weak reference to be used in order to remove self when animation ends.
            WeakReference<View> weakView = new WeakReference<>(animatedBalanceDeltaFrameLayout);

            ViewPropertyAnimator animator = animatedBalanceDeltaFrameLayout.animate()
                    .scaleX(3f).scaleY(3f)
                    .alpha(0f)
                    .setDuration(2500)
                    .translationY(-translationY)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animator) {
                            // Remove the view when animation has ended.
                            // From https://stackoverflow.com/a/7445557
                            // "Android will make an exception when you change the view hierarchy in animationEnd."
                            new Handler().post(() -> {
                                View view = weakView.get();
                                if(view != null) {
                                    ((ViewGroup)view.getParent()).removeView(view);
                                }
                            });
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                        }

                        @Override
                        public void onAnimationCancel(Animator animator) {
                            // Same as onAnimationEnd
                            new Handler().post(() -> {
                                View view = weakView.get();
                                if (view != null) {
                                    ((ViewGroup) view.getParent()).removeView(view);
                                }
                            });
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                        }
                    });

            if (!emitter.isDisposed()) {
                emitter.onNext(animator);
            }
        });
    }

    private void setAllParentsClip(View view, boolean enabled) {
        ViewParent viewParent = view.getParent();
        while (viewParent instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup)viewParent;
            viewGroup.setClipToPadding(enabled);
            viewGroup.setClipChildren(enabled);
            viewParent = viewGroup.getParent();
        }
    }

    private void startActiveSpeedBoostCountDown(long millisDiff) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        countDownTimer = new CountDownTimer(millisDiff, 1000) {
            @Override
            public void onTick(long l) {
                if(!isAdded()) {
                    // Do nothing if not attached to the Activity
                    return;
                }
                String hms = String.format(Locale.US, "%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(l),
                        TimeUnit.MILLISECONDS.toMinutes(l) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(l)),
                        TimeUnit.MILLISECONDS.toSeconds(l) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(l)));
                speedBoostBtn.setText(String.format(Locale.US, "%s - %s",
                        getString(R.string.speed_boost_active_label), hms));
            }

            @Override
            public void onFinish() {
                // update local state
                intentsPublishRelay.accept(PsiCashIntent.GetPsiCashLocal.create());
            }
        }.start();
    }

    public void onTunnelConnectionState(TunnelState status) {
        tunnelConnectionStateBehaviorRelay.accept(status);
    }

    void onOpenHomePage() {
        shouldGetPsiCashRemote.set(true);
    }

    @Override
    public void onDestroy() {
        compositeDisposable.dispose();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        unbindViewState();
        super.onDestroy();
    }

    public void setActiveSpeedBoostListener(ActiveSpeedBoostListener listener) {
        this.activeSpeedBoostListener = listener;
    }

    public interface ActiveSpeedBoostListener {
        void onActiveSpeedBoost(Boolean value);
    }
}
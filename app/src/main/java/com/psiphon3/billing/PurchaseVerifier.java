package com.psiphon3.billing;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import com.android.billingclient.api.Purchase;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.BuildConfig;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PurchaseVerifier {
    private static final String PREFERENCE_PURCHASE_AUTHORIZATION_ID = "preferencePurchaseAuthorization";
    private static final String PREFERENCE_PURCHASE_TOKEN = "preferencePurchaseToken";

    private final AppPreferences appPreferences;
    private final Context context;
    private final PurchaseAuthorizationListener purchaseAuthorizationListener;
    private GooglePlayBillingHelper repository;

    private PublishRelay<TunnelState> tunnelConnectionStatePublishRelay = PublishRelay.create();
    private BehaviorRelay<SubscriptionState> subscriptionStateBehaviorRelay = BehaviorRelay.create();
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    public PurchaseVerifier(Context context, PurchaseAuthorizationListener purchaseAuthorizationListener) {
        this.context = context;
        this.appPreferences = new AppPreferences(context);
        this.repository = GooglePlayBillingHelper.getInstance(context);
        this.purchaseAuthorizationListener = purchaseAuthorizationListener;

        compositeDisposable.add(purchaseVerificationDisposable());
        queryCurrentSubscriptionStatus();
    }

    private Flowable<TunnelState> tunnelConnectionStateFlowable() {
        return tunnelConnectionStatePublishRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private Disposable purchaseVerificationDisposable() {
        return tunnelConnectionStateFlowable()
                .switchMap(tunnelState -> {
                    if (!(tunnelState.isRunning() && tunnelState.connectionData().isConnected())) {
                        // Not connected, do nothing
                        return Flowable.empty();
                    }
                    // Once connected run IAB check and pass the subscription state and
                    // current tunnel state connection data downstream.
                    return repository.subscriptionStateFlowable()
                            .map(subscriptionState -> new Pair<>(subscriptionState, tunnelState.connectionData()));
                })
                .switchMap(pair -> {
                    SubscriptionState subscriptionState = pair.first;
                    TunnelState.ConnectionData connectionData = pair.second;
                    final int httpProxyPort = connectionData.httpPort();
                    if (!subscriptionState.hasValidPurchase()) {
                        if (BuildConfig.SUBSCRIPTION_SPONSOR_ID.equals(connectionData.sponsorId())) {
                            Utils.MyLog.g("PurchaseVerifier: user has no subscription, will restart as non subscriber.");
                            return Flowable.just(UpdateConnectionAction.RESTART_AS_NON_SUBSCRIBER);
                        } else {
                            Utils.MyLog.g("PurchaseVerifier: user has no subscription, continue.");
                            return Flowable.empty();
                        }
                    }
                    // Otherwise check if we have already have an authorization for this token
                    String persistedPurchaseToken = appPreferences.getString(PREFERENCE_PURCHASE_TOKEN, "");
                    String persistedPurchaseAuthorizationId = appPreferences.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    if (persistedPurchaseToken.equals(subscriptionState.purchase().getPurchaseToken()) &&
                            !persistedPurchaseAuthorizationId.isEmpty()) {
                        Utils.MyLog.g("PurchaseVerifier: already have authorization for this purchase, continue.");
                        // We already aware of this purchase, do nothing
                        return Flowable.empty();
                    }
                    // We have a fresh purchase. Store the purchase token and reset the persisted authorization Id
                    Utils.MyLog.g("PurchaseVerifier: user has new valid purchase.");
                    Purchase purchase = subscriptionState.purchase();
                    appPreferences.put(PREFERENCE_PURCHASE_TOKEN, purchase.getPurchaseToken());
                    appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    // Now try and fetch authorization for this purchase
                    boolean isSubscription = GooglePlayBillingHelper.hasUnlimitedSubscription(purchase)
                            || GooglePlayBillingHelper.hasLimitedSubscription(purchase);

                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withProductId(purchase.getSku())
                                    .withIsSubscription(isSubscription)
                                    .withPurchaseToken(purchase.getPurchaseToken())
                                    .withHttpProxyPort(httpProxyPort)
                                    .build();

                    Utils.MyLog.g("PurchaseVerifier: will try and fetch new authorization.");
                    return purchaseVerificationNetworkHelper.fetchAuthorizationFlowable()
                            .map(json -> {
                                        // Note that response with other than 200 HTTP code from the server is
                                        // treated the same as a 200 OK response with empty payload and should result
                                        // in connection restart as a non-subscriber.

                                        if (TextUtils.isEmpty(json)) {
                                            // If payload is empty then do not try to JSON decode.
                                            return UpdateConnectionAction.RESTART_AS_NON_SUBSCRIBER;
                                        }

                                        String encodedAuth = new JSONObject(json).getString("signed_authorization");
                                        Authorization authorization = Authorization.fromBase64Encoded(encodedAuth);
                                        if (authorization == null) {
                                            // Expired or invalid purchase, do nothing.
                                            // No action will be taken next time we receive the same token
                                            // because we persisted this token already.
                                            Utils.MyLog.g("PurchaseVerifier: server returned empty authorization.");
                                            return UpdateConnectionAction.RESTART_AS_NON_SUBSCRIBER;
                                        }

                                        // Persist authorization ID and authorization.
                                        appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, authorization.Id());
                                        // Prior to storing authorization remove all other authorizations of this type
                                        // from storage. Psiphon server will only accept one authorization per access type.
                                        // If there are multiple active authorizations of 'google-subscription' type it is
                                        // not guaranteed the server will select the one associated with current purchase which
                                        // may result in client connect-as-subscriber -> server-reject infinite re-connect loop.
                                        List<Authorization> authorizationsToRemove = new ArrayList<>();
                                        for (Authorization a : Authorization.geAllPersistedAuthorizations(context)) {
                                            if (a.accessType().equals(authorization.accessType())) {
                                                authorizationsToRemove.add(a);
                                            }
                                        }
                                        Authorization.removeAuthorizations(context, authorizationsToRemove);
                                        Authorization.storeAuthorization(context, authorization);
                                        Utils.MyLog.g("PurchaseVerifier: server returned new authorization.");
                                        return UpdateConnectionAction.RESTART_AS_SUBSCRIBER;
                                    }
                            )
                            .doOnError(e -> {
                                Utils.MyLog.g("PurchaseVerifier: fetching authorization failed with error: " + e);
                            })
                            // If we fail HTTP request after all retries for whatever reason do not
                            // restart connection as a non-subscriber. The user may have a legit purchase
                            // and while we can't upgrade the connection we should try and not show home
                            // pages at least.
                            .onErrorResumeNext(Flowable.empty());

                })
                .doOnNext(purchaseAuthorizationListener::updateConnection)
                .subscribe();
    }

    public Single<String> sponsorIdSingle() {
        return repository.subscriptionStateFlowable()
                .firstOrError()
                .doOnSuccess(subscriptionState ->
                        Utils.MyLog.g("PurchaseVerifier: will start with "
                                + (subscriptionState.hasValidPurchase() ? "subscription" : "non-subscription")
                                + " sponsor ID"))
                .map(subscriptionState ->
                        subscriptionState.hasValidPurchase() ?
                                BuildConfig.SUBSCRIPTION_SPONSOR_ID :
                                EmbeddedValues.SPONSOR_ID
                );
    }

    public void onTunnelState(TunnelState tunnelState) {
        tunnelConnectionStatePublishRelay.accept(tunnelState);
    }

    public void onActiveAuthorizationIDs(List<String> acceptedAuthorizationIds) {
        String purchaseAuthorizationID = appPreferences.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

        if (TextUtils.isEmpty(purchaseAuthorizationID)) {
            // There is no persisted authorization, do nothing
            return;
        }

        // If server hasn't accepted any authorizations or persisted authorization ID hasn't been
        // accepted then reset persisted purchase token and trigger new IAB check
        if (acceptedAuthorizationIds.isEmpty() || !acceptedAuthorizationIds.contains(purchaseAuthorizationID)) {
            Utils.MyLog.g("PurchaseVerifier: persisted purchase authorization ID is not active, will query subscription status.");
            appPreferences.put(PREFERENCE_PURCHASE_TOKEN, "");
            appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");
            repository.queryCurrentSubscriptionStatus();
        } else {
            Utils.MyLog.g("PurchaseVerifier: subscription authorization accepted, continue.");
        }
    }

    public void onDestroy() {
        compositeDisposable.dispose();
    }

    public void queryCurrentSubscriptionStatus() {
        repository.startIab();
        repository.queryCurrentSubscriptionStatus();
    }

    public enum UpdateConnectionAction {
        RESTART_AS_NON_SUBSCRIBER,
        RESTART_AS_SUBSCRIBER
    }

    public interface PurchaseAuthorizationListener {
        void updateConnection(UpdateConnectionAction action);
    }
}

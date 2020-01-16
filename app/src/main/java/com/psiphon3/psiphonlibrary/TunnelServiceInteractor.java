/*
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

package com.psiphon3.psiphonlibrary;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.util.Pair;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.jakewharton.rxrelay2.ReplayRelay;
import com.psiphon3.R;
import com.psiphon3.TunnelState;

import net.grandcentrix.tray.AppPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.disposables.Disposable;

public class TunnelServiceInteractor {
    private static final String SERVICE_STARTING_BROADCAST_INTENT = "SERVICE_STARTING_BROADCAST_INTENT";
    private Relay<TunnelState> tunnelStateRelay = BehaviorRelay.<TunnelState>create().toSerialized();
    private Relay<Boolean> dataStatsRelay = PublishRelay.<Boolean>create().toSerialized();
    private Relay<Boolean> knownRegionsRelay = PublishRelay.<Boolean>create().toSerialized();
    private Relay<NfcExchange> nfcExchangeRelay = PublishRelay.<NfcExchange>create().toSerialized();
    private Relay<Pair<Integer, Bundle>> serviceMessageRelay;

    private final Messenger incomingMessenger = new Messenger(new IncomingMessageHandler(this));
    private final Observable<Messenger> serviceMessengerObservable;
    private Disposable restartServiceDisposable = null;
    private Disposable sendMessageDisposable = null;

    private final long bindTimeoutMillis;
    private boolean isPaused = true;

    public TunnelServiceInteractor(Context context) {
        Intent bomIntent = getServiceIntent(context, false);
        Intent vpnIntent = getServiceIntent(context, true);
        Rx2ServiceBindingFactory serviceBindingFactoryBom = new Rx2ServiceBindingFactory(context, bomIntent);
        Rx2ServiceBindingFactory serviceBindingFactoryVpn = new Rx2ServiceBindingFactory(context, vpnIntent);

        serviceMessengerObservable = Observable.merge(serviceBindingFactoryBom.getMessengerObservable(), serviceBindingFactoryVpn.getMessengerObservable());

        // Listen to SERVICE_STARTING_BROADCAST_INTENT broadcast that may be sent by another instance
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SERVICE_STARTING_BROADCAST_INTENT);
        BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (action.equals(SERVICE_STARTING_BROADCAST_INTENT) && !isPaused) {
                        if (sendMessageDisposable != null) {
                            sendMessageDisposable.dispose();
                        }
                        registerWithService(0);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(context).registerReceiver(broadcastReceiver, intentFilter);

        // Calculate binding timeout based on the API level
        if (Build.VERSION.SDK_INT >= 24) {
            bindTimeoutMillis = 250;
        } else if (Build.VERSION.SDK_INT >= 22) {
            bindTimeoutMillis = 400;
        } else if (Build.VERSION.SDK_INT >= 14) {
            bindTimeoutMillis = 600;
        } else {
            bindTimeoutMillis = 1000;
        }
    }

    public void resume() {
        isPaused = false;
        registerWithService(bindTimeoutMillis);
    }

    public void pause() {
        isPaused = true;
        sendServiceMessage(TunnelManager.ClientToServiceMessage.UNREGISTER.ordinal(), null);
        if (sendMessageDisposable != null) {
            sendMessageDisposable.dispose();
        }
    }

    public void startTunnelService(Context context, boolean wantVPN) {
        tunnelStateRelay.accept(TunnelState.unknown());
        Intent intent = getServiceIntent(context, wantVPN);
        try {
            context.startService(intent);
            // Send tunnel starting service broadcast to all instances so they all bind.
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent.setAction(SERVICE_STARTING_BROADCAST_INTENT));
        } catch (SecurityException | IllegalStateException e) {
            Utils.MyLog.g("startTunnelService failed with error: " + e);
            tunnelStateRelay.accept(TunnelState.stopped());
        }
    }

    public void stopTunnelService() {
        tunnelStateRelay.accept(TunnelState.unknown());
        sendServiceMessage(TunnelManager.ClientToServiceMessage.STOP_SERVICE.ordinal(), null);
    }

    public void scheduleRunningTunnelServiceRestart(Context context, Runnable startServiceRunnable) {
        tunnelStateFlowable()
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .timeout(1000, TimeUnit.MILLISECONDS)
                .toMaybe()
                .onErrorResumeNext(Maybe.empty())
                // If the running service doesn't need to be changed from WDM to BOM or vice versa we will
                // just message the service a restart command and have it restart Psiphon tunnel (and VPN
                // if in WDM mode) internally via TunnelManager.onRestartCommand without stopping the service.
                // If the WDM preference has changed we will message the service to stop self, wait for it to
                // stop and then start a brand new service via checkRestartTunnel on a timer.
                .doOnSuccess(tunnelState -> {
                    // If the service is not running do not do anything.
                    if (tunnelState.isRunning()) {
                        AppPreferences appPreferences = new AppPreferences(context);
                        boolean wantVPN = appPreferences
                                .getBoolean(context.getString(R.string.tunnelWholeDevicePreference),
                                        false);

                        if (tunnelState.connectionData().vpnMode() == wantVPN) {
                            commandTunnelRestart();
                        } else {
                            scheduleCompleteServiceRestart(startServiceRunnable);
                        }
                    }
                })
                .subscribe();
    }

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelStateRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<Boolean> dataStatsFlowable() {
        return dataStatsRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<Boolean> knownRegionsFlowable() {
        return knownRegionsRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<NfcExchange> nfcExchangeFlowable() {
        return nfcExchangeRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private Intent getVpnServiceIntent(Context context) {
        return new Intent(context, TunnelVpnService.class);
    }

    private void commandTunnelRestart() {
        sendServiceMessage(TunnelManager.ClientToServiceMessage.RESTART_SERVICE.ordinal(), null);
    }

    private void scheduleCompleteServiceRestart(Runnable startServiceRunnable) {
        if (restartServiceDisposable != null && !restartServiceDisposable.isDisposed()) {
            // call in progress, do nothing
            return;
        }
        // Start observing service connection for disconnected message then command service stop.
        restartServiceDisposable = getMessengerObservableWithTimeout(0)
                .doOnComplete(startServiceRunnable::run)
                .subscribe();
        stopTunnelService();
    }

    private void registerWithService(long timeoutMillis) {
        serviceMessageRelay = ReplayRelay.<Pair<Integer, Bundle>>create().toSerialized();
        if (sendMessageDisposable == null || sendMessageDisposable.isDisposed()) {
            sendMessageDisposable = getMessengerObservableWithTimeout(timeoutMillis)
                    .doOnSubscribe(__ -> tunnelStateRelay.accept(TunnelState.unknown()))
                    .doOnComplete(() -> tunnelStateRelay.accept(TunnelState.stopped()))
                    .doOnDispose(() -> tunnelStateRelay.accept(TunnelState.unknown()))
                    .switchMapCompletable(messenger -> serviceMessageRelay
                            .doOnNext(pair -> {
                                int what = pair.first;
                                Bundle data = pair.second;
                                try {
                                    Message msg = Message.obtain(null, what);
                                    msg.replyTo = incomingMessenger;
                                    if (data != null) {
                                        msg.setData(data);
                                    }
                                    messenger.send(msg);
                                } catch (RemoteException e) {
                                    Utils.MyLog.g(String.format("sendServiceMessage failed: %s", e.getMessage()));
                                }
                            })
                            .ignoreElements())
                    .subscribe();

            sendServiceMessage(TunnelManager.ClientToServiceMessage.REGISTER.ordinal(), null);
        }
    }

    private Observable<Messenger> getMessengerObservableWithTimeout(long timeoutMillis) {
        if (timeoutMillis > 0) {
            return serviceMessengerObservable
                    .timeout(
                            Observable.timer(timeoutMillis, TimeUnit.MILLISECONDS),
                            ignored -> Observable.never()
                    )
                    .onErrorResumeNext(Observable.empty());
        }
        return serviceMessengerObservable;
    }

    private Intent getServiceIntent(Context context, boolean wantVPN) {
        return wantVPN && Utils.hasVpnService() ?
                getVpnServiceIntent(context) : new Intent(context, TunnelService.class);
    }

    private void sendServiceMessage(int what, Bundle data) {
        if (serviceMessageRelay != null) {
            Pair<Integer, Bundle> message = new Pair<>(what, data);
            serviceMessageRelay.accept(message);
        }
    }

    private static TunnelManager.State getTunnelStateFromBundle(Bundle data) {
        TunnelManager.State tunnelState = new TunnelManager.State();
        if (data == null) {
            return tunnelState;
        }
        tunnelState.isRunning = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_RUNNING);
        tunnelState.isVPN = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_VPN);
        tunnelState.isConnected = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_CONNECTED);
        tunnelState.listeningLocalSocksProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT);
        tunnelState.listeningLocalHttpProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT);
        tunnelState.clientRegion = data.getString(TunnelManager.DATA_TUNNEL_STATE_CLIENT_REGION);
        tunnelState.sponsorId = data.getString(TunnelManager.DATA_TUNNEL_STATE_SPONSOR_ID);
        tunnelState.needsHelpConnecting = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_NEEDS_HELP_CONNECTING);
        ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
        if (homePages != null && tunnelState.isConnected) {
            tunnelState.homePages = homePages;
        }
        return tunnelState;
    }

    private static void getDataTransferStatsFromBundle(Bundle data) {
        if (data == null) {
            return;
        }
        data.setClassLoader(DataTransferStats.DataTransferStatsBase.Bucket.class.getClassLoader());
        DataTransferStats.getDataTransferStatsForUI().m_connectedTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_CONNECTED_TIME);
        DataTransferStats.getDataTransferStatsForUI().m_totalBytesSent = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_SENT);
        DataTransferStats.getDataTransferStatsForUI().m_totalBytesReceived = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED);
        DataTransferStats.getDataTransferStatsForUI().m_slowBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS);
        DataTransferStats.getDataTransferStatsForUI().m_slowBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME);
        DataTransferStats.getDataTransferStatsForUI().m_fastBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS);
        DataTransferStats.getDataTransferStatsForUI().m_fastBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME);
    }

    public void importConnectionInfo(String connectionInfoPayload) {
        Bundle data = new Bundle();
        data.putString(TunnelManager.DATA_NFC_CONNECTION_INFO_EXCHANGE_IMPORT, connectionInfoPayload);
        sendServiceMessage(TunnelManager.ClientToServiceMessage.NFC_CONNECTION_INFO_EXCHANGE_IMPORT.ordinal(), data);
    }

    public void nfcExportConnectionInfo() {
        sendServiceMessage(TunnelManager.ClientToServiceMessage.NFC_CONNECTION_INFO_EXCHANGE_EXPORT.ordinal(), null);
    }

    private static class IncomingMessageHandler extends Handler {
        private final WeakReference<TunnelServiceInteractor> weakServiceInteractor;
        private final TunnelManager.ServiceToClientMessage[] scm = TunnelManager.ServiceToClientMessage.values();
        private TunnelManager.State state;


        IncomingMessageHandler(TunnelServiceInteractor serviceInteractor) {
            this.weakServiceInteractor = new WeakReference<>(serviceInteractor);
        }

        @Override
        public void handleMessage(Message msg) {
            TunnelServiceInteractor tunnelServiceInteractor = weakServiceInteractor.get();
            if (tunnelServiceInteractor == null) {
                return;
            }
            if (msg.what > scm.length) {
                super.handleMessage(msg);
                return;
            }
            Bundle data = msg.getData();
            switch (scm[msg.what]) {
                case KNOWN_SERVER_REGIONS:
                    tunnelServiceInteractor.knownRegionsRelay.accept(Boolean.TRUE);
                    break;
                case TUNNEL_CONNECTION_STATE:
                    state = getTunnelStateFromBundle(data);
                    TunnelState tunnelState;
                    if (state.isRunning) {
                        TunnelState.ConnectionData connectionData = TunnelState.ConnectionData.builder()
                                .setIsConnected(state.isConnected)
                                .setClientRegion(state.clientRegion)
                                .setClientVersion(EmbeddedValues.CLIENT_VERSION)
                                .setPropagationChannelId(EmbeddedValues.PROPAGATION_CHANNEL_ID)
                                .setSponsorId(state.sponsorId)
                                .setHttpPort(state.listeningLocalHttpProxyPort)
                                .setVpnMode(state.isVPN)
                                .setHomePages(state.homePages)
                                .setNeedsHelpConnecting(state.needsHelpConnecting)
                                .build();
                        tunnelState = TunnelState.running(connectionData);
                    } else {
                        tunnelState = TunnelState.stopped();
                    }
                    tunnelServiceInteractor.tunnelStateRelay.accept(tunnelState);
                    break;
                case DATA_TRANSFER_STATS:
                    getDataTransferStatsFromBundle(data);
                    tunnelServiceInteractor.dataStatsRelay.accept(state.isConnected);
                    break;
                case NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_EXPORT:
                    tunnelServiceInteractor.nfcExchangeRelay.accept(NfcExchange.exported(data.getString(TunnelManager.DATA_NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_EXPORT)));
                    break;
                case NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_IMPORT:
                    tunnelServiceInteractor.nfcExchangeRelay.accept(NfcExchange.imported(data.getBoolean(TunnelManager.DATA_NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_IMPORT, false)));
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private static class Rx2ServiceBindingFactory {
        private final Observable<Messenger> messengerObservable;
        private ServiceConnection serviceConnection;

        Rx2ServiceBindingFactory(Context context, Intent intent) {
            this.messengerObservable = Observable.using(Connection::new,
                    (final Connection<Messenger> con) -> {
                        serviceConnection = con;
                        context.bindService(intent, con, 0);
                        return Observable.create(con);
                    },
                    __ -> unbind(context))
                    .timeout(
                            Observable.timer(2000, TimeUnit.MILLISECONDS),
                            ignored -> Observable.never()
                    )
                    .onErrorResumeNext(Observable.empty())
                    .replay(1)
                    .refCount();
        }

        Observable<Messenger> getMessengerObservable() {
            return messengerObservable;
        }

        void unbind(Context context) {
            if (serviceConnection != null) {
                try {
                    context.unbindService(serviceConnection);
                    serviceConnection = null;
                } catch (java.lang.IllegalArgumentException e) {
                    // Ignore
                    // "java.lang.IllegalArgumentException: Service not registered"
                }
            }
        }

        private static class Connection<B extends Messenger> implements ServiceConnection, ObservableOnSubscribe<B> {
            private ObservableEmitter<? super B> subscriber;

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (subscriber != null && !subscriber.isDisposed() && service != null) {
                    //noinspection unchecked - we trust this one
                    subscriber.onNext((B) new Messenger(service));
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (subscriber != null && !subscriber.isDisposed()) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void subscribe(ObservableEmitter<B> observableEmitter) throws Exception {
                this.subscriber = observableEmitter;
            }
        }
    }
}

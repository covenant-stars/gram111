package org.telegram.messenger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

/*
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.ProductDetailsResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
*/
import com.google.android.exoplayer2.util.Util;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class BillingController/* implements PurchasesUpdatedListener, BillingClientStateListener */{
    public final static String PREMIUM_PRODUCT_ID = "telegram_premium";
/*
    public final static QueryProductDetailsParams.Product PREMIUM_PRODUCT = QueryProductDetailsParams.Product.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .setProductId(PREMIUM_PRODUCT_ID)
            .build();

    @Nullable
    public static ProductDetails PREMIUM_PRODUCT_DETAILS;
*/

    private static BillingController instance;

/*
    private Map<String, Consumer<BillingResult>> resultListeners = new HashMap<>();
*/
    private List<String> requestingTokens = new ArrayList<>();

    private Map<String, Integer> currencyExpMap = new HashMap<>();

    public static BillingController getInstance() {
        if (instance == null) {
            instance = new BillingController(ApplicationLoader.applicationContext);
        }
        return instance;
    }

/*
    private BillingClient billingClient;
*/

    private BillingController(Context ctx) {
/*
        billingClient = BillingClient.newBuilder(ctx)
                .enablePendingPurchases()
                .setListener(this)
                .build();
*/
    }

    public int getCurrencyExp(String currency) {
        Integer exp = currencyExpMap.get(currency);
        if (exp == null) {
            return 0;
        }
        return exp;
    }

    public void startConnection() {
/*
        if (isReady()) {
            return;
        }
*/
        if (BuildVars.useInvoiceBilling()) {
            try {
                Context ctx = ApplicationLoader.applicationContext;
                InputStream in = ctx.getAssets().open("currencies.json");
                JSONObject obj = new JSONObject(new String(Util.toByteArray(in), "UTF-8"));
                parseCurrencies(obj);
                in.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else {
/*
            billingClient.startConnection(this);
*/
        }
    }

    private void parseCurrencies(JSONObject obj) {
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject currency = obj.optJSONObject(key);
            currencyExpMap.put(key, currency.optInt("exp"));
        }
    }

/*
    public boolean isReady() {
        return billingClient.isReady();
        return false;
    }

    public void queryProductDetails(List<QueryProductDetailsParams.Product> products, ProductDetailsResponseListener responseListener) {
        if (!isReady()) {
            throw new IllegalStateException("Billing controller should be ready for this call!");
        }
        billingClient.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build(), responseListener);
    }

    public void queryPurchases(String productType, PurchasesResponseListener responseListener) {
        billingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(productType).build(), responseListener);
    }

    public boolean startManageSubscription(Context ctx, String productId) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(String.format("https://play.google.com/store/account/subscriptions?sku=%s&package=%s", productId, ctx.getPackageName()))));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    public void addResultListener(String productId, Consumer<BillingResult> listener) {
        resultListeners.put(productId, listener);
    }

    public boolean launchBillingFlow(Activity activity, List<BillingFlowParams.ProductDetailsParams> productDetails) {
        if (!isReady()) {
            return false;
        }
        return billingClient.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetails)
                .build()).getResponseCode() == BillingClient.BillingResponseCode.OK;
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> list) {
        FileLog.d("Billing purchases updated: " + billingResult + ", " + list);
        if (list == null) {
            return;
        }
        for (Purchase purchase : list) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                if (!purchase.isAcknowledged()) {
                    if (!requestingTokens.contains(purchase.getPurchaseToken())) {
                        requestingTokens.add(purchase.getPurchaseToken());
                        TLRPC.TL_payments_assignPlayMarketTransaction req = new TLRPC.TL_payments_assignPlayMarketTransaction();
                        req.purchase_token = purchase.getPurchaseToken();
                        AccountInstance acc = AccountInstance.getInstance(UserConfig.selectedAccount);
                        acc.getConnectionsManager().sendRequest(req, (response, error) -> {
                            if (response instanceof TLRPC.Updates) {
                                acc.getMessagesController().processUpdates((TLRPC.Updates) response, false);
                                requestingTokens.remove(purchase.getPurchaseToken());

                                for (String productId : purchase.getProducts()) {
                                    Consumer<BillingResult> listener = resultListeners.remove(productId);
                                    listener.accept(billingResult);
                                }
                            }
                        });
                    }
                }
            }
        }
    }

    @Override
    public void onBillingServiceDisconnected() {
        FileLog.d("Billing service disconnected");
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult setupBillingResult) {
        if (setupBillingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            queryProductDetails(Collections.singletonList(PREMIUM_PRODUCT), (billingResult, list) -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (ProductDetails details : list) {
                        if (details.getProductId().equals(PREMIUM_PRODUCT_ID)) {
                            PREMIUM_PRODUCT_DETAILS = details;
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.billingProductDetailsUpdated));
                }
            });

            queryPurchases(BillingClient.ProductType.SUBS, this::onPurchasesUpdated);
        }
    }
*/
}

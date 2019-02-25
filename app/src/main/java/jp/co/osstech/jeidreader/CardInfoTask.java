package jp.co.osstech.jeidreader;

import android.content.Intent;
import android.graphics.Bitmap;
import android.nfc.Tag;
import android.os.AsyncTask;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import jp.co.osstech.libjeid.CardEntriesAP;
import jp.co.osstech.libjeid.CardInputHelperAP;
import jp.co.osstech.libjeid.CardType;
import jp.co.osstech.libjeid.InvalidPinException;
import jp.co.osstech.libjeid.JPKIAP;
import jp.co.osstech.libjeid.JPKICertificate;
import jp.co.osstech.libjeid.JeidReader;
import jp.co.osstech.libjeid.in.CardFrontEntries;
import jp.co.osstech.libjeid.in.CardInputHelperEntries;

import org.json.JSONObject;

public class CardInfoTask extends AsyncTask<Void, String, JSONObject>
{
    private static final String TAG = MainActivity.TAG;
    private WeakReference mRef;
    private Tag mNfcTag;
    private String mPin;
    private ProgressDialogFragment mProgress;
    private InvalidPinException ipe;

    public CardInfoTask(CardInfoActivity activity, Tag nfcTag) {
        mRef = new WeakReference<CardInfoActivity>(activity);
        mNfcTag = nfcTag;
    }

    @Override
    protected void onPreExecute() {
        CardInfoActivity activity = (CardInfoActivity)mRef.get();
        if (activity == null) {
            return;
        }
        activity.setMessage("# 読み取り開始、カードを離さないでください");
        activity.hideKeyboard();
        mPin = activity.getPin();
        mProgress = new ProgressDialogFragment();
        mProgress.show(activity.getSupportFragmentManager(), "progress");
    }

    @Override
    protected JSONObject doInBackground(Void... args) {
        if (mPin.isEmpty() || mPin.length() != 4) {
            publishProgress("4桁の暗証番号を入力してください。");
            return null;
        }
        publishProgress("## 券面入力補助APから情報を取得します");
        try {
            JeidReader reader = new JeidReader(mNfcTag);
            CardType type = reader.detectCardType();
            publishProgress("CardType: " + type);
            if (type != CardType.IN) {
                publishProgress("マイナンバーカードではありません");
                return null;
            }
            CardInputHelperAP ap = reader.selectCardInputHelperAP();
            try {
                ap.verifyPin(mPin);
            } catch (InvalidPinException e) {
                ipe = e;
                return null;
            }
            JSONObject obj = new JSONObject();
            String mynumber = ap.getMyNumber();
            obj.put("cardinfo-mynumber", mynumber);
            CardInputHelperEntries entries = ap.getEntries();
            obj.put("cardinfo-name", entries.getName());
            obj.put("cardinfo-birth", entries.getBirth());
            obj.put("cardinfo-sex", entries.getSexString());
            obj.put("cardinfo-addr", entries.getAddr());
            publishProgress(entries.toString());

            JPKIAP jpkiAP = reader.selectJPKIAP();
            JPKICertificate cert = jpkiAP.getAuthCert();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            String certExpireDate = sdf.format(cert.getNotAfter());
            obj.put("cardinfo-cert-expire", certExpireDate);
            //publishProgress("認証用証明書の有効期限: " + certExpireDate);

            publishProgress("券面の読み取り中...");
            CardEntriesAP ap2 = reader.selectCardEntriesAP();
            ap2.verifyPinA(mynumber);
            CardFrontEntries front = ap2.getFrontEntries();
            String expire = front.getExpire();
            obj.put("cardinfo-expire", expire);

            publishProgress("写真のデコード中...");
            Bitmap bitmap = front.getPhotoBitmap();
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os);
            byte[] jpeg = os.toByteArray();
            String src = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.DEFAULT);
            obj.put("cardinfo-photo", src);

            return obj;
        } catch (Exception e) {
            Log.e(TAG, "error at CardInfoTask#doInBackground()", e);
            publishProgress("エラー: " + e);
            return null;
        }
    }

    protected void onProgressUpdate(String... values) {
        CardInfoActivity activity = (CardInfoActivity)mRef.get();
        if (activity == null) {
            return;
        }
        activity.addMessage(values[0]);
    }

    @Override
    protected void onPostExecute(JSONObject obj) {
        mProgress.dismiss();
        CardInfoActivity activity = (CardInfoActivity)mRef.get();
        if (activity == null) {
            return;
        }
        if (ipe != null) {
            int counter = ipe.getCounter();
            String title;
            String msg;
            if (ipe.isBlocked()) {
                title = "PINがブロックされています";
                msg = "市区町村窓口でブロック解除の申請をしてください。";
            } else {
                title = "PINが間違っています";
                msg = "PINを正しく入力してください。";
                msg += "のこり" + counter + "回間違えるとブロックされます。";
            }
            activity.addMessage(title);
            activity.addMessage(msg);
            activity.showInvalidPinDialog(title, msg);
            return;
        }
        if (obj == null) {
            activity.addMessage("エラー: カードを読み取れませんでした。");
            return;
        }

        Intent intent = new Intent(activity, CardInfoViewerActivity.class);
        intent.putExtra("json", obj.toString());
        activity.startActivity(intent);
    }
}

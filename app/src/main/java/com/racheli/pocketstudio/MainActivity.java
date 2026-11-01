package com.racheli.pocketstudio;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PICK_MEDIA = 7401;
    private WebView webView;
    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private Uri selectedMediaUri;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(10,12,22));
        w.setNavigationBarColor(Color.rgb(10,12,22));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(10,12,22));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        webView.addJavascriptInterface(new StudioBridge(), "StudioNative");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*","video/*","audio/*"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, PICK_MEDIA); }
                catch (Exception e) { fileCallback = null; cb.onReceiveValue(null); return false; }
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode != PICK_MEDIA) return;
        Uri[] out = null;
        if (resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<Uri> list = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                ClipData c = data.getClipData();
                for (int n=0;n<c.getItemCount();n++) list.add(c.getItemAt(n).getUri());
            } else if (data.getData()!=null) list.add(data.getData());
            if (!list.isEmpty()) { selectedMediaUri=list.get(0); out=list.toArray(new Uri[0]); }
        }
        if (fileCallback != null) { fileCallback.onReceiveValue(out); fileCallback=null; }
    }

    private void result(String type, boolean ok, String message, String value) {
        try {
            JSONObject o=new JSONObject();
            o.put("type",type); o.put("ok",ok); o.put("message",message);
            if(value!=null)o.put("value",value);
            String js="window.onNativeResult && window.onNativeResult("+o.toString()+")";
            runOnUiThread(()->webView.evaluateJavascript(js,null));
        } catch(Exception ignored) {}
    }

    private class StudioBridge {
        @JavascriptInterface public void saveImage(String base64,String filename,String mime) {
            worker.execute(()->{
                try {
                    String data=base64.contains(",")?base64.substring(base64.indexOf(',')+1):base64;
                    byte[] bytes=android.util.Base64.decode(data,android.util.Base64.DEFAULT);
                    ContentValues v=new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME,safeName(filename,"pocket-studio.jpg"));
                    v.put(MediaStore.Images.Media.MIME_TYPE,mime==null?"image/jpeg":mime);
                    v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/PocketStudio");
                    v.put(MediaStore.Images.Media.IS_PENDING,1);
                    Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
                    if(uri==null)throw new Exception("לא ניתן ליצור קובץ");
                    try(OutputStream os=getContentResolver().openOutputStream(uri)){if(os==null)throw new Exception("לא ניתן לכתוב");os.write(bytes);}
                    ContentValues done=new ContentValues();done.put(MediaStore.Images.Media.IS_PENDING,0);
                    getContentResolver().update(uri,done,null,null);
                    result("saveImage",true,"התמונה נשמרה בגלריה · PocketStudio",uri.toString());
                }catch(Exception e){result("saveImage",false,"שמירה נכשלה: "+e.getMessage(),null);}
            });
        }
        @JavascriptInterface public void trimSelectedMedia(double startMs,double endMs) {
            worker.execute(()->{
                if(selectedMediaUri==null){result("trimMedia",false,"בחרי תחילה קובץ מדיה",null);return;}
                try{Uri uri=trimMedia(selectedMediaUri,Math.max(0,(long)(startMs*1000)),Math.max(0,(long)(endMs*1000)));result("trimMedia",true,"הקטע נשמר במכשיר",uri.toString());}
                catch(Exception e){result("trimMedia",false,"ייצוא נכשל: "+e.getMessage(),null);}
            });
        }
        @JavascriptInterface public void shareLastExport(String uriText) {
            runOnUiThread(()->{
                try{
                    Uri uri=Uri.parse(uriText);String mime=getContentResolver().getType(uri);
                    Intent i=new Intent(Intent.ACTION_SEND);i.setType(mime==null?"*/*":mime);
                    i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i,"שיתוף תוצר"));
                }catch(Exception e){android.widget.Toast.makeText(MainActivity.this,"לא ניתן לשתף",0).show();}
            });
        }
        @JavascriptInterface public String appVersion(){return "1.0.0";}
    }

    private Uri trimMedia(Uri input,long startUs,long endUs)throws Exception{
        if(endUs<=startUs)throw new IllegalArgumentException("טווח לא תקין");
        MediaExtractor ex=new MediaExtractor();ex.setDataSource(this,input,null);
        MediaMuxer mux=null;android.os.ParcelFileDescriptor pfd=null;Uri out=null;
        try{
            int count=ex.getTrackCount();int[] map=new int[count];java.util.Arrays.fill(map,-1);
            boolean media=false;int rotation=0;
            for(int n=0;n<count;n++){MediaFormat f=ex.getTrackFormat(n);String m=f.getString(MediaFormat.KEY_MIME);if(m!=null&&(m.startsWith("video/")||m.startsWith("audio/")))media=true;}
            if(!media)throw new Exception("לא נמצאה רצועת מדיה נתמכת");
            ContentValues v=new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME,safeName("pocket-trimmed-"+System.currentTimeMillis()+".mp4","pocket-trimmed.mp4"));
            v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/PocketStudio");
            v.put(MediaStore.Video.Media.IS_PENDING,1);
            out=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);
            if(out==null)throw new Exception("לא ניתן ליצור קובץ יצוא");
            pfd=getContentResolver().openFileDescriptor(out,"w");
            if(pfd==null)throw new Exception("לא ניתן לפתוח קובץ יצוא");
            mux=new MediaMuxer(pfd.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            for(int n=0;n<count;n++){
                MediaFormat f=ex.getTrackFormat(n);String m=f.getString(MediaFormat.KEY_MIME);
                if(m!=null&&(m.startsWith("video/")||m.startsWith("audio/"))){
                    map[n]=mux.addTrack(f);ex.selectTrack(n);
                    if(m.startsWith("video/")&&f.containsKey("rotation-degrees"))rotation=f.getInteger("rotation-degrees");
                }
            }
            if(rotation!=0)mux.setOrientationHint(rotation);
            ex.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long base=Math.max(0,ex.getSampleTime());
            mux.start();
            ByteBuffer buf=ByteBuffer.allocate(2*1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int written=0;
            while(true){
                int track=ex.getSampleTrackIndex();if(track<0)break;
                long time=ex.getSampleTime();if(time>endUs)break;
                if(time>=base&&map[track]>=0){
                    int size=ex.readSampleData(buf,0);if(size<0)break;
                    info.offset=0;info.size=size;info.presentationTimeUs=Math.max(0,time-base);info.flags=ex.getSampleFlags();
                    mux.writeSampleData(map[track],buf,info);written++;
                }
                ex.advance();
            }
            mux.stop();if(written==0)throw new Exception("לא נמצאו פריימים בטווח");
            ContentValues done=new ContentValues();done.put(MediaStore.Video.Media.IS_PENDING,0);
            getContentResolver().update(out,done,null,null);return out;
        }catch(Exception e){if(out!=null)getContentResolver().delete(out,null,null);throw e;}
        finally{try{if(mux!=null)mux.release();}catch(Exception ignored){}try{ex.release();}catch(Exception ignored){}try{if(pfd!=null)pfd.close();}catch(Exception ignored){}}
    }
    private String safeName(String name,String fallback){if(name==null||name.trim().isEmpty())name=fallback;return name.replaceAll("[^\\p{L}\\p{N}._-]","_");}
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){worker.shutdown();if(webView!=null){webView.removeJavascriptInterface("StudioNative");webView.destroy();}super.onDestroy();}
}

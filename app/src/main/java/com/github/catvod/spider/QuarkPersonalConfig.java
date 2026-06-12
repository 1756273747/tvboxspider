package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.QRCode;
import com.github.catvod.utils.ResUtil;
import com.github.catvod.utils.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 夸克网盘配置线路 Spider
 * 提供登录、退出登录、刷新、使用说明等功能
 * 与 QuarkPersonal 共享 cookie 文件
 */
public class QuarkPersonalConfig extends Spider {

    private static final String COOKIE_FILE = "quark_cookie.json";
    private static final int QR_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int POLL_INTERVAL_MS = 3000;

    private Context savedContext;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private String cookie = "";
    private boolean loginSuccess = false;
    private boolean loginCancelled = false;
    private AlertDialog qrDialog;
    private AlertDialog loadingDialog;

    // ========== Spider 生命周期 ==========

    @Override
    public void init(Context context, String extend) throws Exception {
        SpiderDebug.log("QuarkPersonalConfig init...");
        savedContext = context;
        cookie = readCookieFromFile();
        SpiderDebug.log("QuarkPersonalConfig init done, cookie valid=" + isCookieValid());
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("夸克网盘", "夸克网盘"));
        return Result.string(classes, new LinkedHashMap<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("QuarkPersonalConfig categoryContent tid=" + tid + " pg=" + pg);
        List<Vod> list = new ArrayList<>();

        if (!"夸克网盘".equals(tid)) {
            return Result.get().vod(list).page().string();
        }

        // 只在第一页返回数据，防止框架自动翻页导致卡片重复
        if (!"1".equals(pg)) {
            return Result.get().vod(list).page().string();
        }

        // 四个功能卡片
        boolean loggedIn = isCookieValid();

        // 登录
        Vod loginVod = new Vod();
        loginVod.setVodId("config###登录");
        loginVod.setVodName(loggedIn ? "已登录 (点击重新登录)" : "登录");
        loginVod.setVodPic(loggedIn ? "https://pics2.baidu.com/feed/c9fcc3cec3fdfc033695ac2b9af5e02b343552d2.jpeg" : "https://img1.baidu.com/it/u=1407750889,3441968735&fm=253&fmt=auto&app=138&f=JPEG");
        loginVod.setVodContent(loggedIn ? "当前已登录夸克网盘，点击可重新扫码登录" : "点击扫码登录夸克网盘");
        list.add(loginVod);

        // 退出登录
        Vod logoutVod = new Vod();
        logoutVod.setVodId("config###退出登录");
        logoutVod.setVodName("退出登录");
        logoutVod.setVodPic("https://img0.baidu.com/it/u=2028084904,3939052004&fm=253&fmt=auto&app=138&f=JPEG");
        logoutVod.setVodContent(loggedIn ? "清除本地保存的夸克网盘登录凭证" : "当前未登录");
        list.add(logoutVod);

        // 刷新
        Vod refreshVod = new Vod();
        refreshVod.setVodId("config###刷新");
        refreshVod.setVodName("刷新");
        refreshVod.setVodPic("https://img2.baidu.com/it/u=2567814930,2774475860&fm=253&fmt=auto&app=138&f=JPEG");
        refreshVod.setVodContent("重新获取网盘视频列表（需回到夸克网盘线路查看）");
        list.add(refreshVod);

        // 使用说明
        Vod tutorialVod = new Vod();
        tutorialVod.setVodId("config###使用说明");
        tutorialVod.setVodName("使用说明");
        tutorialVod.setVodPic("https://img1.baidu.com/it/u=1590543876,2233268683&fm=253&fmt=auto&app=138&f=JPEG");
        tutorialVod.setVodContent("查看夸克网盘使用说明");
        list.add(tutorialVod);

        return Result.get().vod(list).page(1, 1, 10, 4).string();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        SpiderDebug.log("QuarkPersonalConfig detailContent ids=" + ids);
        if (ids == null || ids.isEmpty()) return Result.get().string();

        String rawId = ids.get(0);
        if (!rawId.startsWith("config###")) return Result.get().string();

        String action = rawId.substring("config###".length());
        SpiderDebug.log("QuarkPersonalConfig action=" + action);

        switch (action) {
            case "登录":
                // 立即在主线程显示加载对话框，避免用户看到空白页
                showLoadingDialog("正在获取登录二维码...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            doQRLogin();
                        } catch (Exception e) {
                            SpiderDebug.log("QuarkPersonalConfig doQRLogin error: " + e.getMessage());
                            dismissLoadingDialog();
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Notify.show("登录失败: " + e.getMessage());
                                }
                            });
                        }
                    }
                }).start();
                break;
            case "退出登录":
                doLogout();
                break;
            case "刷新":
                doRefresh();
                break;
            case "使用说明":
                showTutorialDialog();
                break;
        }

        // 返回空详情页 - 不设置 vod_play_from/vod_play_url，避免被解析为播放列表
        Vod vod = new Vod();
        vod.setVodId("");
        vod.setVodName(action);
        vod.setVodContent("操作完成，请按返回键回到分类列表");
        // 不要设置 vod_play_from 和 vod_play_url，否则 AbsJsonVod.toXmlVideo() 会创建空的 urlBean.infoList
        // 导致 VodInfo.seriesMap 不为空，DetailActivity 会显示播放页而不是空状态
        List<Vod> list = new ArrayList<>();
        list.add(vod);
        return Result.get().vod(list).string();
    }

    @Override
    public String playerContent(String flag, String id, List<String> flags) throws Exception {
        return Result.get().string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return Result.get().vod(new ArrayList<Vod>()).page().string();
    }

    // ========== Cookie 文件共享 ==========

    private File getCookieFile() {
        return new File(savedContext.getFilesDir(), COOKIE_FILE);
    }

    private String readCookieFromFile() {
        try {
            File cacheFile = getCookieFile();
            if (cacheFile != null && cacheFile.exists()) {
                String content = new BufferedReader(new FileReader(cacheFile)).readLine();
                if (content != null && !content.isEmpty()) {
                    Map<String, Object> json = Json.parseSafe(content, Map.class);
                    if (json != null && json.get("user") != null) {
                        Map<String, Object> user = (Map<String, Object>) json.get("user");
                        Object cookieObj = user.get("cookie");
                        if (cookieObj != null) return String.valueOf(cookieObj);
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonalConfig readCookieFromFile error: " + e.getMessage());
        }
        return "";
    }

    private void writeCookieToFile(String cookieStr) {
        try {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("cookie", cookieStr);
            Map<String, Object> cacheMap = new HashMap<>();
            cacheMap.put("user", userMap);
            String cacheJson = Json.toJson(cacheMap);
            File cacheFile = getCookieFile();
            if (cacheFile != null) {
                FileWriter writer = new FileWriter(cacheFile);
                writer.write(cacheJson);
                writer.close();
                SpiderDebug.log("QuarkPersonalConfig: cookie saved to file");
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonalConfig writeCookieToFile error: " + e.getMessage());
        }
    }

    private boolean isCookieValid() {
        return cookie != null && !cookie.isEmpty() && cookie.contains("__pus");
    }

    // ========== 功能操作 ==========

    private void doLogout() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity activity = Init.getActivity();
                    if (activity == null || activity.isFinishing()) {
                        Notify.show("已退出登录，本地凭证已清除");
                        cookie = "";
                        deleteCookieFile();
                        return;
                    }
                    new AlertDialog.Builder(activity)
                        .setTitle("退出登录")
                        .setMessage("确定要退出夸克网盘登录吗？\n退出后需要重新扫码登录。")
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                cookie = "";
                                deleteCookieFile();
                                Notify.show("已退出登录，本地凭证已清除");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .setCancelable(true)
                        .show();
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig doLogout error: " + e.getMessage());
                }
            }
        });
    }

    private void deleteCookieFile() {
        File cacheFile = getCookieFile();
        if (cacheFile != null && cacheFile.exists()) {
            cacheFile.delete();
        }
        File ackFile = new File(savedContext.getFilesDir(), "quark_tutorial_ack");
        if (ackFile.exists()) ackFile.delete();
    }

    private void doRefresh() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity activity = Init.getActivity();
                    if (activity == null || activity.isFinishing()) {
                        Notify.show("刷新完成");
                        return;
                    }
                    new AlertDialog.Builder(activity)
                        .setTitle("刷新")
                        .setMessage("此操作将重新获取网盘视频列表。\n请回到「夸克网盘」线路查看更新后的列表。")
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Notify.show("刷新完成，请回到夸克网盘线路查看");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .setCancelable(true)
                        .show();
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig doRefresh error: " + e.getMessage());
                }
            }
        });
    }

    // ========== QR 登录系统 ==========

    private void doQRLogin() throws Exception {
        loginSuccess = false;
        loginCancelled = false;
        String token = fetchTokenAndShowQR();
        if (token == null || loginCancelled) return;

        Map<String, String> pollParams = new HashMap<>();
        pollParams.put("token", token);
        pollParams.put("client_id", "532");
        pollParams.put("v", "1.2");
        pollParams.put("request_id", UUID.randomUUID().toString());

        SpiderDebug.log("QuarkPersonalConfig: polling for scan...");
        long startTime = System.currentTimeMillis();
        int pollCount = 0;

        while (!loginSuccess && !loginCancelled) {
            if (System.currentTimeMillis() - startTime > QR_TIMEOUT_MS) {
                SpiderDebug.log("QuarkPersonalConfig: QR code timed out");
                dismissDialog();
                Notify.show("二维码已过期，请重新获取");
                return;
            }

            try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) { break; }
            pollCount++;

            try {
                String pollResult = OkHttp.string("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken", pollParams, new HashMap<>());
                Map<String, Object> pollJson = Json.parseSafe(pollResult, Map.class);
                Object status = pollJson.get("status");

                // 打印每次polling结果以便调试
                SpiderDebug.log("QuarkPersonalConfig: poll result status=" + status);

                boolean success = false;
                if (status instanceof Number) {
                    int statusCode = ((Number) status).intValue();
                    if (statusCode == 2000000) {
                        success = true;
                    } else if (statusCode == 400004) {
                        SpiderDebug.log("QuarkPersonalConfig: QR token expired, refreshing...");
                        Notify.show("二维码已过期，正在刷新...");
                        String newToken = fetchTokenAndShowQR();
                        if (newToken == null || loginCancelled) return;
                        pollParams.put("token", newToken);
                        pollParams.put("request_id", UUID.randomUUID().toString());
                        startTime = System.currentTimeMillis();
                        pollCount = 0;
                        continue;
                    } else {
                        SpiderDebug.log("QuarkPersonalConfig: poll status=" + statusCode + ", not handled");
                    }
                } else {
                    success = "2000000".equals(String.valueOf(status));
                }

                if (success) {
                    String serviceTicket = (String) ((Map<String, Object>) ((Map<String, Object>) pollJson.get("data")).get("members")).get("service_ticket");
                    SpiderDebug.log("QuarkPersonalConfig: got serviceTicket, exchanging for cookie");
                    dismissDialog();
                    exchangeServiceTicket(serviceTicket);
                    loginSuccess = true;
                    break;
                }

                if (pollCount % 5 == 0) {
                    int remaining = (int) ((QR_TIMEOUT_MS - (System.currentTimeMillis() - startTime)) / 1000);
                    updateQrHint("请使用夸克App扫码登录\n剩余时间: " + (remaining / 60) + "分" + (remaining % 60) + "秒\n或点击刷新获取新二维码");
                }

            } catch (Exception e) {
                SpiderDebug.log("QuarkPersonalConfig polling error: " + e.getMessage());
            }
        }

        dismissDialog();
    }

    private String fetchTokenAndShowQR() throws Exception {
        // 正确的 API：获取 token，然后用 token 构造二维码 URL
        String tokenUrl = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin";
        Map<String, String> tokenParams = new HashMap<>();
        tokenParams.put("client_id", "386");
        tokenParams.put("v", "1.2");

        SpiderDebug.log("QuarkPersonalConfig: fetching QR token from " + tokenUrl);
        String tokenResult = OkHttp.string(tokenUrl, tokenParams, new HashMap<>());
        SpiderDebug.log("QuarkPersonalConfig: tokenResult=" + (tokenResult != null ? tokenResult.substring(0, Math.min(200, tokenResult.length())) : "null"));

        Map<String, Object> tokenJson = Json.parseSafe(tokenResult, Map.class);

        if (tokenJson == null) {
            SpiderDebug.log("QuarkPersonalConfig: tokenJson is null, raw response=" + tokenResult);
            dismissLoadingDialog();
            Notify.show("获取二维码失败: 返回数据解析失败");
            return null;
        }

        // 参照 QuarkPersonal.java 的判断逻辑，用 message="ok" 而不是 status 数值判断
        Object message = tokenJson.get("message");
        if (tokenJson == null || !"ok".equals(String.valueOf(message))) {
            SpiderDebug.log("QuarkPersonalConfig: message=" + message);
            dismissLoadingDialog();
            Notify.show("获取二维码失败: API返回错误");
            return null;
        }

        if (!tokenJson.containsKey("data")) {
            SpiderDebug.log("QuarkPersonalConfig: tokenJson has no 'data' field, keys=" + tokenJson.keySet());
            dismissLoadingDialog();
            Notify.show("获取二维码失败: 返回数据格式错误");
            return null;
        }

        Map<String, Object> data = (Map<String, Object>) tokenJson.get("data");
        if (data == null) {
            SpiderDebug.log("QuarkPersonalConfig: data is null");
            dismissLoadingDialog();
            Notify.show("获取二维码失败: data为空");
            return null;
        }

        Map<String, Object> members = (Map<String, Object>) data.get("members");
        if (members == null) {
            SpiderDebug.log("QuarkPersonalConfig: members is null");
            dismissLoadingDialog();
            Notify.show("获取二维码失败: members为空");
            return null;
        }

        Object tokenObj = members.get("token");
        SpiderDebug.log("QuarkPersonalConfig: token=" + tokenObj);

        if (tokenObj == null) {
            SpiderDebug.log("QuarkPersonalConfig: token is null");
            dismissLoadingDialog();
            Notify.show("获取二维码失败: 缺少token");
            return null;
        }

        String token = String.valueOf(tokenObj);
        if (token.isEmpty()) {
            SpiderDebug.log("QuarkPersonalConfig: token is empty");
            dismissLoadingDialog();
            Notify.show("获取二维码失败: token为空");
            return null;
        }

        // 构造二维码 URL（参照 QuarkPersonal.java 的完整参数）
        String qrUrl = "https://su.quark.cn/4_eMHBJ?uc_param_str=&token=" + token + "&client_id=532&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400";
        SpiderDebug.log("QuarkPersonalConfig: qrUrl=" + qrUrl);

        showQRDialog(qrUrl);
        return token;
    }

    private void showQRDialog(String qrUrl) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    dismissLoadingDialog();
                    Activity activity = Init.getActivity();
                    if (activity == null || activity.isFinishing()) {
                        Notify.show("无法显示二维码，请重试");
                        return;
                    }

                    LinearLayout layout = new LinearLayout(activity);
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setGravity(Gravity.CENTER_HORIZONTAL);
                    layout.setPadding(ResUtil.dp2px(24), ResUtil.dp2px(16), ResUtil.dp2px(24), ResUtil.dp2px(16));

                    ImageView imageView = new ImageView(activity);
                    try {
                        int size = ResUtil.dp2px(200);
                        Bitmap qrBitmap = QRCode.getBitmap(qrUrl, size, 2);
                        imageView.setImageBitmap(qrBitmap);
                    } catch (Exception e) {
                        imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                    }
                    layout.addView(imageView);

                    TextView hintView = new TextView(activity);
                    hintView.setText("请使用夸克App扫码登录");
                    hintView.setTextSize(14);
                    hintView.setTextColor(Color.WHITE);
                    hintView.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    hintParams.topMargin = ResUtil.dp2px(12);
                    hintView.setLayoutParams(hintParams);
                    layout.addView(hintView);

                    qrDialog = new AlertDialog.Builder(activity)
                        .setTitle("夸克网盘登录")
                        .setView(layout)
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                loginCancelled = true;
                            }
                        })
                        .setCancelable(false)
                        .show();
                    qrDialog.setCanceledOnTouchOutside(false);
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig showQRDialog error: " + e.getMessage());
                }
            }
        });
    }

    private void updateQrHint(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (qrDialog != null && qrDialog.isShowing()) {
                        LinearLayout layout = (LinearLayout) qrDialog.findViewById(android.R.id.custom);
                        if (layout != null && layout.getChildCount() > 1) {
                            TextView hintView = (TextView) layout.getChildAt(1);
                            if (hintView != null) hintView.setText(text);
                        }
                    }
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig updateQrHint error: " + e.getMessage());
                }
            }
        });
    }

    private void dismissDialog() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (qrDialog != null && qrDialog.isShowing()) {
                        qrDialog.dismiss();
                    }
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig dismissDialog error: " + e.getMessage());
                }
                qrDialog = null;
            }
        });
    }

    private void showLoadingDialog(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    dismissLoadingDialog();
                    Activity activity = Init.getActivity();
                    if (activity == null || activity.isFinishing()) {
                        Notify.show(message);
                        return;
                    }
                    loadingDialog = new AlertDialog.Builder(activity)
                        .setTitle("请稍候")
                        .setMessage(message)
                        .setCancelable(false)
                        .show();
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig showLoadingDialog error: " + e.getMessage());
                }
            }
        });
    }

    private void dismissLoadingDialog() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (loadingDialog != null && loadingDialog.isShowing()) {
                        loadingDialog.dismiss();
                    }
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig dismissLoadingDialog error: " + e.getMessage());
                }
                loadingDialog = null;
            }
        });
    }

    private void exchangeServiceTicket(String serviceTicket) {
        try {
            // 参照 QuarkPersonal.java 的正确实现：用 pan.quark.cn/account/info 接口，从 set-Cookie 响应头提取 cookie
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.put("Referer", "https://pan.quark.cn/");
            OkResult result = OkHttp.get("https://pan.quark.cn/account/info?st=" + serviceTicket + "&lw=scan", new HashMap<>(), headers);
            Map<String, Object> json = Json.parseSafe(result.getBody(), Map.class);

            SpiderDebug.log("QuarkPersonalConfig: exchangeServiceTicket response body=" + (result.getBody() != null ? result.getBody().substring(0, Math.min(200, result.getBody().length())) : "null"));

            if (json != null && Boolean.TRUE.equals(json.get("success"))) {
                List<String> cookies = result.getResp().get("set-Cookie");
                if (cookies != null && !cookies.isEmpty()) {
                    List<String> cookieList = new ArrayList<>();
                    for (String c : cookies) {
                        cookieList.add(c.split(";")[0]);
                    }
                    cookie = TextUtils.join(";", cookieList);
                    writeCookieToFile(cookie);
                    Notify.show("登录成功！");
                    SpiderDebug.log("QuarkPersonalConfig: login success, cookie length=" + cookie.length());
                } else {
                    SpiderDebug.log("QuarkPersonalConfig: login response has no set-Cookie header");
                    Notify.show("登录成功但未获取到Cookie");
                }
            } else {
                SpiderDebug.log("QuarkPersonalConfig: exchangeServiceTicket success=" + (json != null ? json.get("success") : "null"));
                Notify.show("登录失败，请重试");
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonalConfig exchangeServiceTicket error: " + e.getMessage());
            Notify.show("登录失败: " + e.getMessage());
        }
    }

    // ========== 使用说明系统 ==========

    private void showTutorialDialog() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity activity = Init.getActivity();
                    if (activity == null || activity.isFinishing()) {
                        Notify.show(buildTutorialContent());
                        return;
                    }

                    android.widget.ScrollView scrollView = new android.widget.ScrollView(activity);
                    scrollView.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(12), ResUtil.dp2px(16), ResUtil.dp2px(12));

                    TextView textView = new TextView(activity);
                    textView.setText(buildTutorialContent());
                    textView.setTextSize(14);
                    textView.setTextColor(Color.WHITE);
                    textView.setBackgroundColor(Color.parseColor("#333333"));
                    textView.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12), ResUtil.dp2px(12));
                    textView.setLineSpacing(0, 1.3f);
                    scrollView.addView(textView);

                    new AlertDialog.Builder(activity)
                        .setTitle("使用说明")
                        .setView(scrollView)
                        .setPositiveButton("知道了", null)
                        .setCancelable(true)
                        .show();
                } catch (Exception e) {
                    SpiderDebug.log("QuarkPersonalConfig showTutorialDialog error: " + e.getMessage());
                    Notify.show(buildTutorialContent());
                }
            }
        });
    }

    private String buildTutorialContent() {
        return "【夸克网盘使用说明】\n\n"
            + "1. 首次使用需要在本配置线路中扫码登录夸克网盘，登录后会自动保存凭证。\n"
            + "2. 登录成功后，请回到「夸克网盘」线路浏览视频。\n"
            + "3. 如果视频列表没有更新，可以在本配置线路点击「刷新」后回到夸克网盘线路查看。\n"
            + "4. 如需更换账号，先「退出登录」再重新「登录」。\n\n"
            + "【注意事项】\n"
            + "- 网盘根目录下需要有视频文件夹，子文件夹会自动作为分类显示。\n"
            + "- 支持自动识别封面图片和简介文件。\n"
            + "- 支持搜索网盘内的文件夹。";
    }
}

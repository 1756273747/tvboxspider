package com.github.catvod.spider;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkResult;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.ProxyServer;
import com.github.catvod.utils.ProxyVideo;
import com.github.catvod.utils.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度网盘个人网盘 TVBox Spider
 * 支持动态分类、递归扫描、电影/剧集区分、搜索、封面图片、使用说明、刮削
 */
public class BaiduPersonal extends Spider {

    private String rootPath = "/视频";
    private String defaultPic = "";
    private List<String> picExts = Arrays.asList("jpg", "jpeg", "png", "webp");
    private List<String> videoExts = Arrays.asList("mp4", "mkv", "avi", "wmv", "flv", "iso", "mpg", "ts", "m2ts", "mov");
    private String infoFileName = "info";
    private List<String> seriesCategories = Arrays.asList("电视剧", "综艺", "动漫", "纪录片");
    private Map<String, String> categoryNameMap = new HashMap<>();

    private static final String BD_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; SM-X800) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.40 Safari/537.36";
    private static final String BD_APP_USER_AGENT = "netdisk;P2SP;2.2.91.136;android-android;";

    private String cookie = "";
    private String bdstoken = "";
    private Context savedContext = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int vipType = 0;

    private static class Folder {
        private String path;
        private String name;
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    private static class FileItem {
        private long fsId;
        private String path;
        private String name;
        private int isdir;
        private long size;
        private String thumbUrl;
        public long getFsId() { return fsId; }
        public void setFsId(long fsId) { this.fsId = fsId; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getIsdir() { return isdir; }
        public void setIsdir(int isdir) { this.isdir = isdir; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public String getThumbUrl() { return thumbUrl; }
        public void setThumbUrl(String thumbUrl) { this.thumbUrl = thumbUrl; }
        public boolean isFolder() { return isdir == 1; }
        public boolean isFile() { return isdir == 0; }
    }

    private static final String BD_COOKIE_FILE = "baidu_cookie.json";
    private static final String BD_API_HOST = "https://pan.baidu.com";

    @Override
    public void init(Context context, String extend) throws Exception {
        SpiderDebug.log("BaiduPersonal init...");
        savedContext = context;
        
        if (extend != null && !extend.isEmpty()) {
            try {
                Map<String, Object> ext = Json.parseSafe(extend, Map.class);
                if (ext != null) {
                    if (ext.containsKey("rootPath")) rootPath = String.valueOf(ext.get("rootPath"));
                    if (ext.containsKey("defaultPic")) defaultPic = String.valueOf(ext.get("defaultPic"));
                    if (ext.containsKey("infoFileName")) infoFileName = String.valueOf(ext.get("infoFileName"));
                    if (ext.containsKey("picExts") && ext.get("picExts") instanceof List) {
                        picExts = new ArrayList<>();
                        for (Object o : (List<?>) ext.get("picExts")) picExts.add(String.valueOf(o));
                    }
                    if (ext.containsKey("seriesCategories") && ext.get("seriesCategories") instanceof List) {
                        seriesCategories = new ArrayList<>();
                        for (Object o : (List<?>) ext.get("seriesCategories")) seriesCategories.add(String.valueOf(o));
                    }
                }
            } catch (Exception e) {
                SpiderDebug.log("BaiduPersonal parseConfig error: " + e.getMessage());
            }
        }

        cookie = readCookieFromFile();
        SpiderDebug.log("BaiduPersonal init cookie length=" + cookie.length());

        if (cookie.isEmpty() || !cookie.contains("BDUSS")) {
            SpiderDebug.log("BaiduPersonal: cookie invalid, please login via Config line first");
            Notify.show("请先使用「配置」线路扫码登录百度网盘");
        } else {
            try {
                checkVip();
            } catch (Exception e) {
                SpiderDebug.log("BaiduPersonal checkVip error: " + e.getMessage());
            }
        }
        SpiderDebug.log("BaiduPersonal init done, cookie valid=" + (cookie.contains("BDUSS")) + " length=" + cookie.length() + " vipType=" + vipType);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        SpiderDebug.log("BaiduPersonal homeContent cookie=[" + cookie + "] containsBDUSS=" + cookie.contains("BDUSS"));
        List<Class> classes = new ArrayList<>();

        // 重新读取 cookie，确保最新
        if (cookie.isEmpty() || !cookie.contains("BDUSS")) {
            cookie = readCookieFromFile();
            SpiderDebug.log("BaiduPersonal homeContent re-read cookie length=" + cookie.length() + " containsBDUSS=" + cookie.contains("BDUSS"));
        }

        if (cookie.isEmpty() || !cookie.contains("BDUSS")) {
            SpiderDebug.log("BaiduPersonal: cookie invalid, showing login hint");
            classes.add(new Class("请先登录", "请先登录"));
            return Result.string(classes, new LinkedHashMap<>());
        }

        try {
            SpiderDebug.log("BaiduPersonal homeContent listing rootPath=" + rootPath);
            List<Folder> folders = listFolders(rootPath);
            SpiderDebug.log("BaiduPersonal homeContent rootPath folders count=" + folders.size());
            categoryNameMap.clear();
            for (Folder folder : folders) {
                classes.add(new Class(folder.getPath(), folder.getName()));
                categoryNameMap.put(folder.getPath(), folder.getName());
            }
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal homeContent rootPath error: " + e.getMessage());
            try {
                List<Folder> rootFolders = listFolders("/");
                SpiderDebug.log("BaiduPersonal homeContent root dir folders count=" + rootFolders.size());
                categoryNameMap.clear();
                for (Folder folder : rootFolders) {
                    classes.add(new Class(folder.getPath(), folder.getName()));
                    categoryNameMap.put(folder.getPath(), folder.getName());
                }
            } catch (Exception e2) {
                SpiderDebug.log("BaiduPersonal homeContent root dir error: " + e2.getMessage());
                Notify.show("读取网盘目录失败: " + e2.getMessage());
            }
        }

        if (classes.isEmpty()) {
            classes.add(new Class("暂无内容", "暂无内容"));
        }

        return Result.string(classes, new LinkedHashMap<>());
    }

    private Map<String, List<FileItem>> categoryCache = new HashMap<>();

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("BaiduPersonal categoryContent tid=" + tid + " pg=" + pg);
        List<Vod> list = new ArrayList<>();

        if ("请先登录".equals(tid)) {
            Notify.show("请先在「配置」线路中扫码登录百度网盘");
            return Result.get().vod(list).page().string();
        }

        int page = (pg == null || pg.isEmpty()) ? 1 : Integer.parseInt(pg);
        int pageSize = 30;
        int total = 0;
        int pagecount = 1;

        try {
            String dirPath = tid;
            String categoryName = categoryNameMap.get(tid);
            if (categoryName == null) categoryName = getFolderName(dirPath);

            List<FileItem> subFolders = categoryCache.get(dirPath);
            if (subFolders == null) {
                Notify.show("正在加载视频列表...");
                List<FileItem> items = listAllFileItems(dirPath);
                subFolders = new ArrayList<>();
                for (FileItem item : items) {
                    if (item.isFolder()) subFolders.add(item);
                }
                categoryCache.put(dirPath, subFolders);
                SpiderDebug.log("BaiduPersonal categoryContent loaded " + subFolders.size() + " sub-folders for path=" + dirPath);
            }

            total = subFolders.size();
            pagecount = (int) Math.ceil((double) total / pageSize);
            if (pagecount < 1) pagecount = 1;

            // 防止无限循环：如果请求的页码超出范围，设置pagecount使APP停止加载
            // APP端逻辑：page++ → if(page > pagecount) → loadMoreEnd()
            // 所以pagecount必须 < page才能让APP停止
            if (page > pagecount) {
                SpiderDebug.log("BaiduPersonal categoryContent page=" + page + " > pagecount=" + pagecount + ", set pagecount=" + (page - 1) + " to stop loading");
                pagecount = page - 1;
            }

            SpiderDebug.log("BaiduPersonal categoryContent path=" + dirPath + " tid=" + tid + " page=" + page + "/" + pagecount + " subFolders=" + subFolders.size());
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, subFolders.size());

            for (int i = start; i < end; i++) {
                FileItem sub = subFolders.get(i);
                Vod vod = new Vod();
                String safeCategoryName = categoryName != null ? categoryName : "";
                String vodId = safeCategoryName + "###" + sub.getName() + "###" + sub.getPath();
                vod.setVodId(vodId);
                vod.setVodName(sub.getName());
                String pic = findPicInFolder(sub.getPath());
                vod.setVodPic(pic != null && !pic.isEmpty() ? pic : defaultPic);
                vod.setVodRemarks("");
                list.add(vod);
            }
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal categoryContent error: " + e.getMessage());
            Notify.show("加载分类失败: " + e.getMessage());
        }

        return Result.get().vod(list).page(page, pagecount, pageSize, total).string();
    }

    private String readTextFile(String path, long fsId) {
        try {
            String downloadUrl = getDownloadUrl(fsId);
            if (downloadUrl == null || downloadUrl.isEmpty()) return "";

            // .nfo/txt文件很小，直接用App UA下载，不走ProxyServer
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", BD_APP_USER_AGENT);
            headers.put("Cookie", cookie);
            String content = OkHttp.string(downloadUrl, new HashMap<>(), headers);
            SpiderDebug.log("BaiduPersonal readTextFile path=" + path + " contentLen=" + (content != null ? content.length() : 0));
            return content != null ? content : "";
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal readTextFile error: " + e.getMessage());
        }
        return "";
    }

    private static class NfoInfo {
        String title;
        String plot;
        String director;
        String actor;
        String genre;
        String country;
        String year;
        String runtime;
    }

    private NfoInfo parseNfo(String xmlContent) {
        NfoInfo info = new NfoInfo();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xmlContent)));

            org.w3c.dom.NodeList plots = doc.getElementsByTagName("plot");
            if (plots.getLength() > 0) {
                String plot = plots.item(0).getTextContent();
                if (plot != null && !plot.trim().isEmpty()) info.plot = plot.trim();
            }
            if (info.plot == null || info.plot.isEmpty()) {
                org.w3c.dom.NodeList outlines = doc.getElementsByTagName("outline");
                if (outlines.getLength() > 0) {
                    String outline = outlines.item(0).getTextContent();
                    if (outline != null && !outline.trim().isEmpty()) info.plot = outline.trim();
                }
            }
            org.w3c.dom.NodeList titles = doc.getElementsByTagName("title");
            if (titles.getLength() > 0) info.title = titles.item(0).getTextContent();
            org.w3c.dom.NodeList directors = doc.getElementsByTagName("director");
            if (directors.getLength() > 0) info.director = directors.item(0).getTextContent();
            org.w3c.dom.NodeList actors = doc.getElementsByTagName("actor");
            StringBuilder actorSb = new StringBuilder();
            for (int i = 0; i < actors.getLength(); i++) {
                org.w3c.dom.Element actorEl = (org.w3c.dom.Element) actors.item(i);
                org.w3c.dom.NodeList names = actorEl.getElementsByTagName("name");
                if (names.getLength() > 0) {
                    String name = names.item(0).getTextContent();
                    if (name != null && !name.trim().isEmpty()) {
                        if (actorSb.length() > 0) actorSb.append(" / ");
                        actorSb.append(name.trim());
                    }
                }
            }
            if (actorSb.length() > 0) info.actor = actorSb.toString();
            org.w3c.dom.NodeList genres = doc.getElementsByTagName("genre");
            StringBuilder genreSb = new StringBuilder();
            for (int i = 0; i < genres.getLength(); i++) {
                String genre = genres.item(i).getTextContent();
                if (genre != null && !genre.trim().isEmpty()) {
                    if (genreSb.length() > 0) genreSb.append(" / ");
                    genreSb.append(genre.trim());
                }
            }
            if (genreSb.length() > 0) info.genre = genreSb.toString();
            org.w3c.dom.NodeList countries = doc.getElementsByTagName("country");
            if (countries.getLength() > 0) info.country = countries.item(0).getTextContent();
            org.w3c.dom.NodeList years = doc.getElementsByTagName("year");
            if (years.getLength() > 0) info.year = years.item(0).getTextContent();
            org.w3c.dom.NodeList runtimes = doc.getElementsByTagName("runtime");
            if (runtimes.getLength() > 0) info.runtime = runtimes.item(0).getTextContent();
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal parseNfo error: " + e.getMessage());
        }
        return info;
    }

    private String findPicInFolder(String dirPath) {
        try {
            SpiderDebug.log("BaiduPersonal findPicInFolder path=" + dirPath);
            List<FileItem> items = listFileItems(dirPath);
            SpiderDebug.log("BaiduPersonal findPicInFolder items=" + items.size());
            for (FileItem item : items) {
                if (isPicFile(item.getName())) {
                    SpiderDebug.log("BaiduPersonal findPicInFolder found pic=" + item.getName() + " thumbUrl=" + (item.getThumbUrl() != null ? item.getThumbUrl().substring(0, Math.min(60, item.getThumbUrl().length())) : "null"));
                    if (item.getThumbUrl() != null && !item.getThumbUrl().isEmpty()) {
                        return item.getThumbUrl();
                    }
                }
            }
            SpiderDebug.log("BaiduPersonal findPicInFolder no pic found in path=" + dirPath);
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal findPicInFolder error: " + e.getMessage());
        }
        return null;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        SpiderDebug.log("BaiduPersonal detailContent id=" + ids.get(0));
        String rawId = ids.get(0);

        String path;
        String categoryName;
        String folderName;
        if (rawId.contains("###")) {
            String[] parts = rawId.split("###", 3);
            categoryName = parts[0];
            if (parts.length >= 3) {
                folderName = parts[1];
                path = parts[2];
            } else {
                folderName = "";
                path = parts[1];
            }
        } else {
            path = rawId;
            categoryName = extractCategoryFromPath(path);
            folderName = "";
        }

        SpiderDebug.log("BaiduPersonal detailContent parsed path=" + path + " folderName=" + folderName + " categoryName=" + categoryName);

        List<FileItem> items;
        try {
            items = listFileItems(path);
            SpiderDebug.log("BaiduPersonal detailContent listFileItems returned " + items.size() + " items");
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal detailContent listFileItems error: " + e.getMessage());
            items = new ArrayList<>();
        }

        List<FileItem> videos = new ArrayList<>();
        List<FileItem> subFolders = new ArrayList<>();
        String picUrl = "";
        NfoInfo nfoInfo = null;
        String txtContent = "";

        for (FileItem item : items) {
            if (item.isFolder()) {
                subFolders.add(item);
            } else if (isVideoFile(item.getName())) {
                videos.add(item);
                SpiderDebug.log("BaiduPersonal detailContent found video: " + item.getName());
            } else if (isPicFile(item.getName())) {
                if (picUrl.isEmpty() && item.getThumbUrl() != null && !item.getThumbUrl().isEmpty()) {
                    picUrl = item.getThumbUrl();
                    SpiderDebug.log("BaiduPersonal detailContent found pic: " + item.getName());
                }
            } else if (isInfoFile(item.getName())) {
                String content = readTextFile(item.getPath(), item.getFsId());
                if (content != null && !content.isEmpty()) {
                    String ext = getExtension(item.getName()).toLowerCase();
                    if (ext.equals("nfo")) {
                        nfoInfo = parseNfo(content);
                        SpiderDebug.log("BaiduPersonal detailContent parsed nfo title=" + (nfoInfo.title != null ? nfoInfo.title : "null"));
                    } else if (txtContent.isEmpty()) {
                        txtContent = content;
                        SpiderDebug.log("BaiduPersonal detailContent txt content length=" + txtContent.length());
                    }
                }
            }
        }

        String infoText = (nfoInfo != null && nfoInfo.plot != null) ? nfoInfo.plot : txtContent;
        SpiderDebug.log("BaiduPersonal detailContent videos=" + videos.size() + " subFolders=" + subFolders.size() + " picUrl=" + (!picUrl.isEmpty() ? picUrl.substring(0, Math.min(80, picUrl.length())) : "empty"));

        Collections.sort(videos, new Comparator<FileItem>() {
            @Override
            public int compare(FileItem a, FileItem b) {
                return naturalOrderCompare(a.getName(), b.getName());
            }
        });

        String vodName;
        if (!folderName.isEmpty()) {
            vodName = folderName;
        } else if (nfoInfo != null && nfoInfo.title != null && !nfoInfo.title.isEmpty()) {
            vodName = nfoInfo.title;
        } else {
            vodName = getFolderName(path);
        }
        if (vodName.isEmpty()) vodName = categoryName;
        if (vodName.isEmpty()) vodName = "未知";

        for (FileItem sub : subFolders) {
            try {
                List<FileItem> subItems = listFileItems(sub.getPath());
                List<FileItem> subVideos = filterVideos(subItems);
                SpiderDebug.log("BaiduPersonal detailContent subFolder=" + sub.getName() + " videos=" + subVideos.size());
                if (!subVideos.isEmpty()) {
                    Collections.sort(subVideos, new Comparator<FileItem>() {
                        @Override
                        public int compare(FileItem a, FileItem b) {
                            return naturalOrderCompare(a.getName(), b.getName());
                        }
                    });
                    videos.addAll(subVideos);
                }
            } catch (Exception e) {
                SpiderDebug.log("BaiduPersonal detailContent sub-folder error: " + e.getMessage());
            }
        }

        Vod vod = new Vod();
        vod.setVodId(rawId);
        vod.setVodName(vodName);
        vod.setVodPic(!picUrl.isEmpty() ? picUrl : defaultPic);
        vod.setVodContent(infoText != null ? infoText : "");
        if (nfoInfo != null) {
            if (nfoInfo.director != null && !nfoInfo.director.isEmpty()) vod.setVodDirector(nfoInfo.director);
            if (nfoInfo.actor != null && !nfoInfo.actor.isEmpty()) vod.setVodActor(nfoInfo.actor);
            if (nfoInfo.genre != null && !nfoInfo.genre.isEmpty()) vod.setVodRemarks(nfoInfo.genre);
            if (nfoInfo.country != null && !nfoInfo.country.isEmpty()) vod.setVodArea(nfoInfo.country);
            if (nfoInfo.year != null && !nfoInfo.year.isEmpty()) vod.setVodYear(nfoInfo.year);
        }

        Vod.VodPlayBuilder builder = new Vod.VodPlayBuilder();
        List<Vod.VodPlayBuilder.PlayUrl> allUrls = new ArrayList<>();
        List<Vod.VodPlayBuilder.PlayUrl> mainUrls = buildPlayUrls(videos);
        allUrls.addAll(mainUrls);
        SpiderDebug.log("BaiduPersonal detailContent mainUrls=" + mainUrls.size());

        if (!allUrls.isEmpty()) {
            builder.append("百度原画", allUrls);
        }

        Vod.VodPlayBuilder.BuildResult result = builder.build();
        SpiderDebug.log("BaiduPersonal detailContent vodPlayFrom=" + result.vodPlayFrom + " vodPlayUrl length=" + (result.vodPlayUrl != null ? result.vodPlayUrl.length() : 0));
        vod.setVodPlayFrom(result.vodPlayFrom);
        vod.setVodPlayUrl(result.vodPlayUrl);

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        SpiderDebug.log("BaiduPersonal playerContent flag=" + flag + " id=" + id);
        String rawId = id.replace("+++++", "");
        String[] parts = rawId.split("\\|");
        long fsId = Long.parseLong(parts[0]);
        String filePath = parts.length > 1 ? parts[1] : "";

        String dlink = getDownloadUrl(fsId);

        if (dlink == null || dlink.isEmpty()) {
            Notify.show("获取播放链接失败，请检查Cookie是否过期");
            return Result.get().url("").string();
        }

        // 通过APP本地代理播放，确保Range头正确转发
        // 百度CDN要求：App UA + Cookie + Range头，缺一不可
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", BD_APP_USER_AGENT);
        header.put("Cookie", cookie);

        String proxyUrl = ProxyVideo.buildCommonProxyUrl(dlink, header);
        SpiderDebug.log("BaiduPersonal playerContent proxyUrl=" + proxyUrl.substring(0, Math.min(80, proxyUrl.length())));

        return Result.get()
            .url(proxyUrl)
            .header(header)
            .string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        SpiderDebug.log("BaiduPersonal searchContent key=" + key);
        List<Vod> list = new ArrayList<>();

        if (cookie.isEmpty() || !cookie.contains("BDUSS")) {
            Notify.show("请先登录百度网盘");
            return Result.get().vod(list).page().string();
        }

        try {
            String encodedKey = encodeUrl(key);
            String searchUrl = BD_API_HOST + "/rest/2.0/xpan/file?method=search&key=" + encodedKey + "&dir=/";
            Map<String, String> headers = getApiHeaders();
            String result = OkHttp.string(searchUrl, new HashMap<>(), headers);
            Map<String, Object> json = Json.parseSafe(result, Map.class);

            if (json != null && json.get("list") != null) {
                List<Map<String, Object>> items = (List<Map<String, Object>>) json.get("list");
                if (items != null) {
                    for (Map<String, Object> item : items) {
                        int isdir = 0;
                        Object isdirObj = item.get("isdir");
                        if (isdirObj instanceof Number) isdir = ((Number) isdirObj).intValue();
                        else isdir = Integer.parseInt(String.valueOf(isdirObj));

                        if (isdir == 1) {
                            String path = String.valueOf(item.get("path"));
                            String name = String.valueOf(item.get("server_filename"));

                            Vod vod = new Vod();
                            vod.setVodId(path);
                            vod.setVodName(name);
                            vod.setVodPic(defaultPic);
                            vod.setVodRemarks("搜索结果");
                            list.add(vod);
                        }
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal searchContent error: " + e.getMessage());
            Notify.show("搜索失败: " + e.getMessage());
        }

        return Result.get().vod(list).page().string();
    }

    // ========== Cookie持久化 ==========

    private File getCookieFile() {
        File file = new File(savedContext.getFilesDir(), BD_COOKIE_FILE);
        SpiderDebug.log("BaiduPersonal getCookieFile path=" + file.getAbsolutePath() + " exists=" + file.exists());
        return file;
    }

    private String readCookieFromFile() {
        try {
            File cacheFile = getCookieFile();
            if (cacheFile != null && cacheFile.exists()) {
                String content = new BufferedReader(new FileReader(cacheFile)).readLine();
                if (content != null && !content.isEmpty()) {
                    Map<String, Object> json = Json.parseSafe(content, Map.class);
                    if (json != null && json.get("cookie") != null) {
                        return String.valueOf(json.get("cookie"));
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("BaiduPersonal readCookieFromFile error: " + e.getMessage());
        }
        return "";
    }

    // ========== 网盘API ==========

    private List<Folder> listFolders(String dirPath) throws Exception {
        String url = BD_API_HOST + "/rest/2.0/xpan/file?method=list&dir=" + encodeUrl(dirPath) + "&web=web&start=0&limit=100&order=name&desc=0";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        SpiderDebug.log("BaiduPersonal listFolders path=" + dirPath + " result=" + (result != null ? result.substring(0, Math.min(200, result.length())) : "null"));
        Map<String, Object> json = Json.parseSafe(result, Map.class);

        List<Folder> folders = new ArrayList<>();
        if (json != null) {
            Object errno = json.get("errno");
            Object errmsg = json.get("errmsg");
            SpiderDebug.log("BaiduPersonal listFolders errno=" + errno + " errmsg=" + errmsg);
            if (json.get("list") != null) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) json.get("list");
                if (list != null) {
                    SpiderDebug.log("BaiduPersonal listFolders list size=" + list.size());
                    for (Map<String, Object> itemData : list) {
                        int isdir = 0;
                        Object isdirObj = itemData.get("isdir");
                        if (isdirObj instanceof Number) isdir = ((Number) isdirObj).intValue();
                        else isdir = Integer.parseInt(String.valueOf(isdirObj));

                        if (isdir == 1) {
                            Folder folder = new Folder();
                            folder.setPath(String.valueOf(itemData.get("path")));
                            folder.setName(String.valueOf(itemData.get("server_filename")));
                            folders.add(folder);
                        }
                    }
                }
            }
        }
        return folders;
    }

    private List<FileItem> listFileItems(String dirPath) throws Exception {
        String url = BD_API_HOST + "/rest/2.0/xpan/file?method=list&dir=" + encodeUrl(dirPath) + "&web=web&start=0&limit=200&order=name&desc=0";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);

        List<FileItem> items = new ArrayList<>();
        if (json != null && json.get("list") != null) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) json.get("list");
            if (list != null) {
                for (Map<String, Object> itemData : list) {
                    FileItem item = new FileItem();
                    Object fsid = itemData.get("fs_id");
                    if (fsid instanceof Number) item.setFsId(((Number) fsid).longValue());
                    else item.setFsId(Long.parseLong(String.valueOf(fsid)));
                    item.setPath(String.valueOf(itemData.get("path")));
                    item.setName(String.valueOf(itemData.get("server_filename")));
                    int isdir = 0;
                    Object isdirObj = itemData.get("isdir");
                    if (isdirObj instanceof Number) isdir = ((Number) isdirObj).intValue();
                    else isdir = Integer.parseInt(String.valueOf(isdirObj));
                    item.setIsdir(isdir);
                    Object sizeObj = itemData.get("size");
                    long size = 0;
                    if (sizeObj instanceof Number) size = ((Number) sizeObj).longValue();
                    item.setSize(size);
                    // 解析缩略图URL（百度API返回thumbs字段）
                    Object thumbsObj = itemData.get("thumbs");
                    if (thumbsObj instanceof Map) {
                        Map<String, Object> thumbs = (Map<String, Object>) thumbsObj;
                        // 尝试获取不同尺寸的缩略图，优先使用较大尺寸
                        String[] thumbKeys = {"url3", "url2", "url1"};
                        for (String key : thumbKeys) {
                            Object thumbUrl = thumbs.get(key);
                            if (thumbUrl != null && !String.valueOf(thumbUrl).isEmpty()) {
                                item.setThumbUrl(String.valueOf(thumbUrl));
                                break;
                            }
                        }
                    }
                    items.add(item);
                }
            }
        }
        return items;
    }

    private List<FileItem> listAllFileItems(String dirPath) throws Exception {
        List<FileItem> allItems = new ArrayList<>();
        int start = 0;
        int limit = 200;
        boolean hasMore = true;

        while (hasMore) {
            String url = BD_API_HOST + "/rest/2.0/xpan/file?method=list&dir=" + encodeUrl(dirPath) + "&web=web&start=" + start + "&limit=" + limit + "&order=name&desc=0";
            Map<String, String> headers = getApiHeaders();
            String result = OkHttp.string(url, new HashMap<>(), headers);
            Map<String, Object> json = Json.parseSafe(result, Map.class);

            if (json != null && json.get("list") != null) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) json.get("list");
                if (list != null) {
                    for (Map<String, Object> itemData : list) {
                        FileItem item = new FileItem();
                        Object fsid = itemData.get("fs_id");
                        if (fsid instanceof Number) item.setFsId(((Number) fsid).longValue());
                        else item.setFsId(Long.parseLong(String.valueOf(fsid)));
                        item.setPath(String.valueOf(itemData.get("path")));
                        item.setName(String.valueOf(itemData.get("server_filename")));
                        int isdir = 0;
                        Object isdirObj = itemData.get("isdir");
                        if (isdirObj instanceof Number) isdir = ((Number) isdirObj).intValue();
                        else isdir = Integer.parseInt(String.valueOf(isdirObj));
                        item.setIsdir(isdir);
                        Object sizeObj = itemData.get("size");
                        long size = 0;
                        if (sizeObj instanceof Number) size = ((Number) sizeObj).longValue();
                        item.setSize(size);
                        allItems.add(item);
                    }
                    if (list.size() < limit) {
                        hasMore = false;
                    } else {
                        start += limit;
                    }
                } else {
                    hasMore = false;
                }
            } else {
                hasMore = false;
            }
        }

        SpiderDebug.log("BaiduPersonal listAllFileItems path=" + dirPath + " total=" + allItems.size());
        return allItems;
    }

    private void checkVip() throws Exception {
        String url = BD_API_HOST + "/rest/2.0/xpan/nas?method=uinfo";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);
        if (json != null && json.get("vip_type") != null) {
            Object vip = json.get("vip_type");
            if (vip instanceof Number) vipType = ((Number) vip).intValue();
            else vipType = Integer.parseInt(String.valueOf(vip));
        }
        SpiderDebug.log("BaiduPersonal VIP check result: vipType=" + vipType);
    }

    private void refreshBdstoken() throws Exception {
        if (!bdstoken.isEmpty()) return;
        String url = BD_API_HOST + "/api/gettemplatevariable?clienttype=0&app_id=250528&web=1&fields=[\"bdstoken\",\"token\",\"uk\"]";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);
        if (json != null && json.get("result") != null) {
            Map<String, Object> res = (Map<String, Object>) json.get("result");
            Object token = res.get("bdstoken");
            if (token != null) {
                bdstoken = String.valueOf(token);
                SpiderDebug.log("BaiduPersonal bdstoken refreshed: " + bdstoken);
            }
        }
    }

    private String getDownloadUrl(long fsId) throws Exception {
        refreshBdstoken();
        String url = BD_API_HOST + "/api/filemetas?target=fsids&fsids=[" + fsId + "]&dlink=1&bdstoken=" + bdstoken;
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);

        if (json != null && json.get("info") != null) {
            List<Map<String, Object>> infoList = (List<Map<String, Object>>) json.get("info");
            if (infoList != null && !infoList.isEmpty()) {
                Object dlink = infoList.get(0).get("dlink");
                if (dlink != null) {
                    String dlinkStr = String.valueOf(dlink);
                    // 直接返回dlink，由APP代理服务器处理302重定向和Range头
                    // 百度CDN要求App UA + Cookie + Range，通过代理确保这些条件满足
                    SpiderDebug.log("BaiduPersonal getDownloadUrl dlink=" + dlinkStr.substring(0, Math.min(80, dlinkStr.length())));
                    return dlinkStr;
                }
            }
        }
        SpiderDebug.log("BaiduPersonal getDownloadUrl failed: " + result);
        return null;
    }

    private String getVideoPlayUrl(String path, long fsId) throws Exception {
        String url = BD_API_HOST + "/api/mediainfo?type=VideoURL&path=" + encodeUrl(path)
            + "&fs_id=" + fsId + "&devuid=&clienttype=1&nom3u8=1&dlink=1&media=1&origin=dlna";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);

        if (json != null && json.get("media") != null) {
            Map<String, Object> media = (Map<String, Object>) json.get("media");
            Object dlink = media.get("dlink");
            if (dlink != null) return String.valueOf(dlink);
        }
        return null;
    }

    /**
     * 获取M3U8转码播放地址
     * 使用百度网盘转码接口，返回M3U8播放列表
     * M3U8格式对Range头要求不严格，更适合播放
     */
    private String getM3U8PlayUrl(String path, long fsId) throws Exception {
        refreshBdstoken();
        
        // 百度网盘转码接口，获取M3U8播放地址
        // type参数: M3U8_FLV_264_480=480P, M3U8_FLV_264_720=720P, M3U8_FLV_264_1080=1080P
        String quality = "M3U8_FLV_264_480"; // 默认480P
        if (vipType >= 2) {
            quality = "M3U8_FLV_264_1080"; // 超级VIP可用1080P
        } else if (vipType == 1) {
            quality = "M3U8_FLV_264_720"; // 普通VIP可用720P
        }
        
        String url = BD_API_HOST + "/api/mediainfo?type=" + quality + "&path=" + encodeUrl(path)
            + "&fs_id=" + fsId + "&clienttype=80&origin=dlna";
        
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        SpiderDebug.log("BaiduPersonal getM3U8PlayUrl result=" + (result != null ? result.substring(0, Math.min(200, result.length())) : "null"));
        
        Map<String, Object> json = Json.parseSafe(result, Map.class);
        if (json != null) {
            // 尝试获取M3U8地址
            if (json.get("info") != null) {
                Map<String, Object> info = (Map<String, Object>) json.get("info");
                // 转码后的M3U8地址通常在info中
                Object m3u8Url = info.get("url");
                if (m3u8Url != null) {
                    String m3u8 = String.valueOf(m3u8Url);
                    SpiderDebug.log("BaiduPersonal getM3U8PlayUrl m3u8=" + m3u8.substring(0, Math.min(80, m3u8.length())));
                    return m3u8;
                }
            }
            // 如果上面的路径不对，尝试其他可能的路径
            if (json.get("media") != null) {
                Map<String, Object> media = (Map<String, Object>) json.get("media");
                Object m3u8Url = media.get("url");
                if (m3u8Url != null) {
                    return String.valueOf(m3u8Url);
                }
            }
        }
        
        SpiderDebug.log("BaiduPersonal getM3U8PlayUrl failed, fallback to getVideoPlayUrl");
        // 如果M3U8获取失败，回退到普通视频播放地址
        return getVideoPlayUrl(path, fsId);
    }

    private Map<String, String> getApiHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BD_USER_AGENT);
        headers.put("Referer", "https://pan.baidu.com/");
        headers.put("Cookie", cookie);
        return headers;
    }

    // ========== 工具方法 ==========

    private List<FileItem> filterVideos(List<FileItem> items) {
        List<FileItem> videos = new ArrayList<>();
        for (FileItem item : items) {
            if (isVideoFile(item.getName())) videos.add(item);
        }
        return videos;
    }

    private boolean isVideoFile(String name) {
        String ext = getExtension(name).toLowerCase();
        return videoExts.contains(ext);
    }

    private boolean isPicFile(String name) {
        String ext = getExtension(name).toLowerCase();
        return picExts.contains(ext);
    }

    private boolean isInfoFile(String name) {
        String ext = getExtension(name).toLowerCase();
        return ext.equals("nfo") || ext.equals("txt");
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    private String encodeUrl(String str) {
        try {
            StringBuilder sb = new StringBuilder();
            for (char c : str.toCharArray()) {
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                    sb.append(c);
                } else {
                    byte[] bytes = String.valueOf(c).getBytes("UTF-8");
                    for (byte b : bytes) {
                        sb.append('%');
                        sb.append(String.format("%02X", b & 0xFF));
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            try {
                return java.net.URLEncoder.encode(str, "UTF-8");
            } catch (Exception e2) {
                return str;
            }
        }
    }

    private String getFolderName(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String extractCategoryFromPath(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] parts = path.split("/");
        return parts.length > 0 ? parts[0] : "";
    }

    private List<Vod.VodPlayBuilder.PlayUrl> buildPlayUrls(List<FileItem> videos) {
        List<Vod.VodPlayBuilder.PlayUrl> urls = new ArrayList<>();
        for (FileItem video : videos) {
            Vod.VodPlayBuilder.PlayUrl pu = new Vod.VodPlayBuilder.PlayUrl();
            pu.name = video.getName();
            pu.url = "+++++" + video.getFsId() + "|" + video.getPath();
            urls.add(pu);
        }
        return urls;
    }

    private static int naturalOrderCompare(String a, String b) {
        Pattern pattern = Pattern.compile("(\\D*)(\\d*)");
        Matcher ma = pattern.matcher(a);
        Matcher mb = pattern.matcher(b);
        while (ma.find() && mb.find()) {
            int cmp = ma.group(1).compareTo(mb.group(1));
            if (cmp != 0) return cmp;
            String na = ma.group(2), nb = mb.group(2);
            if (!na.isEmpty() || !nb.isEmpty()) {
                try {
                    cmp = Integer.compare(Integer.parseInt(na.isEmpty() ? "0" : na), Integer.parseInt(nb.isEmpty() ? "0" : nb));
                    if (cmp != 0) return cmp;
                } catch (NumberFormatException e) {
                    cmp = na.compareTo(nb);
                    if (cmp != 0) return cmp;
                }
            }
        }
        return a.compareTo(b);
    }
}

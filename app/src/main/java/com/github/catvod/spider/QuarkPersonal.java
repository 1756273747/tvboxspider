package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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
import com.github.catvod.utils.ProxyServer;
import com.github.catvod.utils.ResUtil;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 夸克个人网盘TVBox Spider
 * 支持动态分类、递归扫描、电影/剧集区分、搜索、封面图片、使用说明、刮削
 */
public class QuarkPersonal extends Spider {

    private String rootPath = "\u89c6\u9891";
    private String defaultPic = "";
    private List<String> picExts = Arrays.asList("jpg", "jpeg", "png", "webp");
    private List<String> videoExts = Arrays.asList("mp4", "mkv", "avi", "wmv", "flv", "iso", "mpg", "ts", "m2ts", "mov");
    private String infoFileName = "info";
    private List<String> seriesCategories = Arrays.asList("\u7535\u89c6\u5267", "\u7efc\u827a", "\u52a8\u6f2b", "\u7eaa\u5f55\u7247");
    private Map<String, String> categoryNameMap = new HashMap<>();

    private String cookie = "";
    private Context savedContext = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isVip = false;

    private static class Folder {
        private String fid;
        private String name;
        public String getFid() { return fid; }
        public void setFid(String fid) { this.fid = fid; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    private static class FileItem {
        private String fid;
        private String name;
        private int fileType;
        private long size;
        private String bigThumbnail;
        private String previewUrl;
        public String getFid() { return fid; }
        public void setFid(String fid) { this.fid = fid; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getFileType() { return fileType; }
        public void setFileType(int fileType) { this.fileType = fileType; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
        public String getBigThumbnail() { return bigThumbnail; }
        public void setBigThumbnail(String bigThumbnail) { this.bigThumbnail = bigThumbnail; }
        public String getPreviewUrl() { return previewUrl; }
        public void setPreviewUrl(String previewUrl) { this.previewUrl = previewUrl; }
        public boolean isFolder() { return fileType == 0; }
        public boolean isFile() { return fileType == 1; }
    }

    private static Activity getActivityFromContext(Context context) {
        if (context instanceof Activity) return (Activity) context;
        if (context instanceof ContextWrapper) return getActivityFromContext(((ContextWrapper) context).getBaseContext());
        return null;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        SpiderDebug.log("QuarkPersonal init...");
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
                SpiderDebug.log("QuarkPersonal parseConfig error: " + e.getMessage());
            }
        }

        cookie = readCookieFromFile();
        SpiderDebug.log("QuarkPersonal init cookie length=" + cookie.length());

        if (cookie.isEmpty() || !cookie.contains("__pus")) {
            SpiderDebug.log("QuarkPersonal: cookie invalid, please login via Config line first");
            Notify.show("\u8bf7\u5148\u4f7f\u7528\u300c\u914d\u7f6e\u300d\u7ebf\u8def\u626b\u7801\u767b\u5f55\u5938\u514b\u7f51\u76d8");
        } else {
            try {
                checkVip();
            } catch (Exception e) {
                SpiderDebug.log("QuarkPersonal checkVip error: " + e.getMessage());
            }
        }
        SpiderDebug.log("QuarkPersonal init done, cookie valid=" + (cookie.contains("__pus")) + " length=" + cookie.length() + " isVip=" + isVip);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        SpiderDebug.log("QuarkPersonal homeContent...");
        List<Class> classes = new ArrayList<>();

        if (cookie.isEmpty() || !cookie.contains("__pus")) {
            SpiderDebug.log("QuarkPersonal: cookie invalid, showing login hint");
            classes.add(new Class("\u8bf7\u5148\u767b\u5f55", "\u8bf7\u5148\u767b\u5f55"));
            return Result.string(classes, new LinkedHashMap<>());
        }

        try {
            List<Folder> folders = listFolders(rootPath);
            categoryNameMap.clear();
            for (Folder folder : folders) {
                classes.add(new Class(folder.getFid(), folder.getName()));
                categoryNameMap.put(folder.getFid(), folder.getName());
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal homeContent rootPath error: " + e.getMessage());
            try {
                List<Folder> rootFolders = listFolders("");
                categoryNameMap.clear();
                for (Folder folder : rootFolders) {
                    classes.add(new Class(folder.getFid(), folder.getName()));
                    categoryNameMap.put(folder.getFid(), folder.getName());
                }
            } catch (Exception e2) {
                SpiderDebug.log("QuarkPersonal homeContent root dir error: " + e2.getMessage());
                Notify.show("\u8bfb\u53d6\u7f51\u76d8\u76ee\u5f55\u5931\u8d25: " + e2.getMessage());
            }
        }

        if (classes.isEmpty()) {
            classes.add(new Class("\u6682\u65e0\u5185\u5bb9", "\u6682\u65e0\u5185\u5bb9"));
        }

        return Result.string(classes, new LinkedHashMap<>());
    }

    private Map<String, List<FileItem>> categoryCache = new HashMap<>();

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("QuarkPersonal categoryContent tid=" + tid + " pg=" + pg);
        List<Vod> list = new ArrayList<>();

        if ("\u8bf7\u5148\u767b\u5f55".equals(tid)) {
            Notify.show("\u8bf7\u5148\u5728\u300c\u914d\u7f6e\u300d\u7ebf\u8def\u4e2d\u626b\u7801\u767b\u5f55\u5938\u514b\u7f51\u76d8");
            return Result.get().vod(list).page().string();
        }

        int page = (pg == null || pg.isEmpty()) ? 1 : Integer.parseInt(pg);
        int pageSize = 30;
        int total = 0;
        int pagecount = 1;

        try {
            String categoryFid;
            if (tid.matches("[0-9a-f]{32}")) {
                categoryFid = tid;
            } else {
                categoryFid = getFidByPath(rootPath + "/" + tid);
            }
            String categoryName = categoryNameMap.get(tid);
            if (categoryName == null) categoryName = tid;

            List<FileItem> subFolders = categoryCache.get(categoryFid);
            if (subFolders == null) {
                Notify.show("\u6b63\u5728\u52a0\u8f7d\u89c6\u9891\u5217\u8868...");
                List<FileItem> items = listAllFileItems(categoryFid);
                subFolders = new ArrayList<>();
                for (FileItem item : items) {
                    if (item.isFolder()) subFolders.add(item);
                }
                categoryCache.put(categoryFid, subFolders);
                SpiderDebug.log("QuarkPersonal categoryContent loaded " + subFolders.size() + " sub-folders for fid=" + categoryFid);
            }

            total = subFolders.size();
            pagecount = (int) Math.ceil((double) total / pageSize);
            if (pagecount < 1) pagecount = 1;
            SpiderDebug.log("QuarkPersonal categoryContent fid=" + categoryFid + " tid=" + tid + " page=" + page + "/" + pagecount + " subFolders=" + subFolders.size());
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, subFolders.size());

            for (int i = start; i < end; i++) {
                FileItem sub = subFolders.get(i);
                Vod vod = new Vod();
                String safeCategoryName = categoryName != null ? categoryName : "";
                String vodId = safeCategoryName + "###" + sub.getName() + "###" + sub.getFid();
                vod.setVodId(vodId);
                vod.setVodName(sub.getName());
                String pic = findPicInFolder(sub.getFid(), sub.getName());
                vod.setVodPic(pic != null && !pic.isEmpty() ? pic : defaultPic);
                vod.setVodRemarks("");
                list.add(vod);
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal categoryContent error: " + e.getMessage());
            Notify.show("\u52a0\u8f7d\u5206\u7c7b\u5931\u8d25: " + e.getMessage());
        }

        return Result.get().vod(list).page(page, pagecount, pageSize, total).string();
    }

    private String readTextFile(String fid) {
        try {
            String url = "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&uc_param_str=";
            Map<String, String> headers = getApiHeaders();
            Map<String, Object> body = new HashMap<>();
            body.put("fids", Arrays.asList(fid));
            OkResult result = OkHttp.post(url, Json.toJson(body), headers);
            String resp = result.getBody();
            SpiderDebug.log("QuarkPersonal readTextFile fid=" + fid + " downloadRespCode=" + result.getCode());
            Map<String, Object> json = Json.parseSafe(resp, Map.class);
            if (json != null && json.get("data") != null) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) json.get("data");
                if (list != null && !list.isEmpty()) {
                    Object downloadUrl = list.get(0).get("download_url");
                    if (downloadUrl != null) {
                        String dlUrlStr = String.valueOf(downloadUrl);
                        SpiderDebug.log("QuarkPersonal readTextFile downloadUrl=" + dlUrlStr.substring(0, Math.min(100, dlUrlStr.length())));
                        // 通过 ProxyServer 代理下载，确保IP和会话一致
                        Map<String, String> dlHeaders = new HashMap<>();
                        dlHeaders.put("Cookie", headers.get("Cookie"));
                        dlHeaders.put("Referer", "https://pan.quark.cn/");
                        dlHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
                        String proxyUrl = ProxyServer.INSTANCE.buildProxyUrl(dlUrlStr, dlHeaders);
                        SpiderDebug.log("QuarkPersonal readTextFile proxyUrl=" + proxyUrl.substring(0, Math.min(100, proxyUrl.length())));
                        String content = OkHttp.string(proxyUrl, new HashMap<>(), new HashMap<>());
                        String preview = content != null && content.length() > 0 ? content.substring(0, Math.min(100, content.length())) : "null";
                        SpiderDebug.log("QuarkPersonal readTextFile contentLen=" + (content != null ? content.length() : 0) + " preview=" + preview);
                        return content != null ? content : "";
                    }
                }
            } else {
                SpiderDebug.log("QuarkPersonal readTextFile json parse failed resp=" + (resp != null ? resp.substring(0, Math.min(100, resp.length())) : "null"));
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal readTextFile error: " + e.getMessage());
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
            org.w3c.dom.Document doc = builder.parse(new org.xml.sax.InputSource(new StringReader(xmlContent)));

            // 提取 plot
            org.w3c.dom.NodeList plots = doc.getElementsByTagName("plot");
            if (plots.getLength() > 0) {
                String plot = plots.item(0).getTextContent();
                if (plot != null && !plot.trim().isEmpty()) {
                    info.plot = plot.trim();
                }
            }
            // 如果没有 plot，提取 outline
            if (info.plot == null || info.plot.isEmpty()) {
                org.w3c.dom.NodeList outlines = doc.getElementsByTagName("outline");
                if (outlines.getLength() > 0) {
                    String outline = outlines.item(0).getTextContent();
                    if (outline != null && !outline.trim().isEmpty()) {
                        info.plot = outline.trim();
                    }
                }
            }
            // 提取 title
            org.w3c.dom.NodeList titles = doc.getElementsByTagName("title");
            if (titles.getLength() > 0) {
                info.title = titles.item(0).getTextContent();
            }
            // 提取 director
            org.w3c.dom.NodeList directors = doc.getElementsByTagName("director");
            if (directors.getLength() > 0) {
                info.director = directors.item(0).getTextContent();
            }
            // 提取 actor（多个actor节点）
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
            if (actorSb.length() > 0) {
                info.actor = actorSb.toString();
            }
            // 提取 genre（多个genre节点）
            org.w3c.dom.NodeList genres = doc.getElementsByTagName("genre");
            StringBuilder genreSb = new StringBuilder();
            for (int i = 0; i < genres.getLength(); i++) {
                String genre = genres.item(i).getTextContent();
                if (genre != null && !genre.trim().isEmpty()) {
                    if (genreSb.length() > 0) genreSb.append(" / ");
                    genreSb.append(genre.trim());
                }
            }
            if (genreSb.length() > 0) {
                info.genre = genreSb.toString();
            }
            // 提取 country
            org.w3c.dom.NodeList countries = doc.getElementsByTagName("country");
            if (countries.getLength() > 0) {
                info.country = countries.item(0).getTextContent();
            }
            // 提取 year
            org.w3c.dom.NodeList years = doc.getElementsByTagName("year");
            if (years.getLength() > 0) {
                info.year = years.item(0).getTextContent();
            }
            // 提取 runtime
            org.w3c.dom.NodeList runtimes = doc.getElementsByTagName("runtime");
            if (runtimes.getLength() > 0) {
                info.runtime = runtimes.item(0).getTextContent();
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal parseNfo error: " + e.getMessage());
        }
        return info;
    }

    private String findPicInFolder(String fid, String folderName) {
        try {
            List<FileItem> items = listFileItems(fid);
            for (FileItem item : items) {
                if (isPicFile(item.getName())) {
                    String previewUrl = item.getPreviewUrl();
                    if (previewUrl != null && !previewUrl.isEmpty()) {
                        return previewUrl;
                    }
                }
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal findPicInFolder error: " + e.getMessage());
        }
        return null;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        SpiderDebug.log("QuarkPersonal detailContent id=" + ids.get(0));
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
        } else if (rawId.startsWith("fid:/")) {
            path = rawId.substring(5).split("/", 2)[0];
            categoryName = "";
            folderName = "";
        } else {
            path = rawId;
            categoryName = extractCategoryFromPath(path);
            folderName = "";
        }

        SpiderDebug.log("QuarkPersonal detailContent parsed path=" + path + " folderName=" + folderName + " categoryName=" + categoryName);

        // 刷新__puus，确保Cookie有效
        refreshPus();

        List<FileItem> items;
        try {
            items = listFileItems(path);
            SpiderDebug.log("QuarkPersonal detailContent listFileItems returned " + items.size() + " items");
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal detailContent listFileItems error: " + e.getMessage());
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
                SpiderDebug.log("QuarkPersonal detailContent found video: " + item.getName());
            } else if (isPicFile(item.getName())) {
                // 任意图片文件，取第一张的 preview_url
                if (picUrl.isEmpty()) {
                    String previewUrl = item.getPreviewUrl();
                    if (previewUrl != null && !previewUrl.isEmpty()) {
                        picUrl = previewUrl;
                        SpiderDebug.log("QuarkPersonal detailContent found pic: " + item.getName());
                    }
                }
            } else if (isInfoFile(item.getName())) {
                // 优先读取 nfo 文件，其次 txt
                String content = readTextFile(item.getFid());
                if (content != null && !content.isEmpty()) {
                    String ext = getExtension(item.getName()).toLowerCase();
                    if (ext.equals("nfo")) {
                        nfoInfo = parseNfo(content);
                        SpiderDebug.log("QuarkPersonal detailContent parsed nfo title=" + (nfoInfo.title != null ? nfoInfo.title : "null") + " director=" + (nfoInfo.director != null ? nfoInfo.director : "null") + " actor=" + (nfoInfo.actor != null ? nfoInfo.actor : "null"));
                    } else if (txtContent.isEmpty()) {
                        // txt 作为备选，且只取第一个
                        txtContent = content;
                        SpiderDebug.log("QuarkPersonal detailContent txt content length=" + txtContent.length());
                    }
                }
            }
        }

        String infoText = (nfoInfo != null && nfoInfo.plot != null) ? nfoInfo.plot : txtContent;
        SpiderDebug.log("QuarkPersonal detailContent videos=" + videos.size() + " subFolders=" + subFolders.size() + " picUrl=" + (!picUrl.isEmpty() ? picUrl.substring(0, Math.min(80, picUrl.length())) : "empty") + " infoText=" + (infoText != null && infoText.length() > 0 ? infoText.substring(0, Math.min(100, infoText.length())) : "empty"));

        SpiderDebug.log("QuarkPersonal detailContent videos=" + videos.size() + " subFolders=" + subFolders.size());

        Collections.sort(videos, new Comparator<FileItem>() {
            @Override
            public int compare(FileItem a, FileItem b) {
                return naturalOrderCompare(a.getName(), b.getName());
            }
        });

        String vodName;
        if (!folderName.isEmpty()) {
            vodName = folderName;
        } else if (path.length() >= 32) {
            vodName = categoryName;
        } else {
            vodName = getFolderName(path);
        }
        if (vodName.isEmpty()) vodName = categoryName;
        if (vodName.isEmpty()) vodName = "\u672a\u77e5";

        Vod vod = new Vod();
        vod.setVodId(path);
        vod.setVodName(vodName);
        vod.setVodPic(!picUrl.isEmpty() ? picUrl : defaultPic);
        vod.setVodContent(infoText != null ? infoText : "");
        // 设置NFO解析的额外字段
        if (nfoInfo != null) {
            if (nfoInfo.director != null && !nfoInfo.director.isEmpty()) {
                vod.setVodDirector(nfoInfo.director);
            }
            if (nfoInfo.actor != null && !nfoInfo.actor.isEmpty()) {
                vod.setVodActor(nfoInfo.actor);
            }
            if (nfoInfo.genre != null && !nfoInfo.genre.isEmpty()) {
                vod.setVodRemarks(nfoInfo.genre);
            }
            if (nfoInfo.country != null && !nfoInfo.country.isEmpty()) {
                vod.setVodArea(nfoInfo.country);
            }
            if (nfoInfo.year != null && !nfoInfo.year.isEmpty()) {
                vod.setVodYear(nfoInfo.year);
            }
        }

        Vod.VodPlayBuilder builder = new Vod.VodPlayBuilder();
        List<Vod.VodPlayBuilder.PlayUrl> allUrls = new ArrayList<>();
        List<Vod.VodPlayBuilder.PlayUrl> mainUrls = buildPlayUrls(videos);
        allUrls.addAll(mainUrls);
        SpiderDebug.log("QuarkPersonal detailContent mainUrls=" + mainUrls.size());
        for (FileItem sub : subFolders) {
            try {
                List<FileItem> subItems = listFileItems(sub.getFid());
                List<FileItem> subVideos = filterVideos(subItems);
                SpiderDebug.log("QuarkPersonal detailContent subFolder=" + sub.getName() + " videos=" + subVideos.size());
                if (!subVideos.isEmpty()) {
                    List<Vod.VodPlayBuilder.PlayUrl> subUrls = buildPlayUrls(subVideos);
                    allUrls.addAll(subUrls);
                }
            } catch (Exception e) {
                SpiderDebug.log("QuarkPersonal detailContent sub-folder error: " + e.getMessage());
            }
        }
        SpiderDebug.log("QuarkPersonal detailContent allUrls=" + allUrls.size());
        if (!allUrls.isEmpty()) {
            // 为每种清晰度添加一条线路
            List<String> formats = getPlayFormatList();
            for (String format : formats) {
                builder.append("\u5938\u514b" + format, allUrls);
            }
            // 额外添加原画线路
            builder.append("\u5938\u514b\u539f\u753b", allUrls);
        }

        Vod.VodPlayBuilder.BuildResult result = builder.build();
        SpiderDebug.log("QuarkPersonal detailContent vodPlayFrom=" + result.vodPlayFrom + " vodPlayUrl length=" + (result.vodPlayUrl != null ? result.vodPlayUrl.length() : 0));
        vod.setVodPlayFrom(result.vodPlayFrom);
        vod.setVodPlayUrl(result.vodPlayUrl);

        return Result.string(vod);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        SpiderDebug.log("QuarkPersonal playerContent flag=" + flag + " id=" + id);
        String fileId = id.replace("+++++", "");
        SpiderDebug.log("QuarkPersonal playerContent fileId=" + fileId + " cookie length=" + cookie.length());

        Map<String, String> header = getApiHeaders();
        header.remove("Host");
        header.remove("Content-Type");

        if (flag.contains("\u539f\u753b")) {
            // 原画：直接下载
            String downloadUrl = getDownloadUrl(fileId);
            SpiderDebug.log("QuarkPersonal playerContent downloadUrl=" + (downloadUrl != null ? downloadUrl.substring(0, Math.min(100, downloadUrl.length())) : "null"));
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                Notify.show("\u83b7\u53d6\u64ad\u653e\u94fe\u63a5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5Cookie\u662f\u5426\u8fc7\u671f");
                return Result.get().url("").string();
            }
            return Result.get().url(ProxyServer.INSTANCE.buildProxyUrl(downloadUrl, header)).octet().header(header).string();
        } else {
            // 转码：调用file/v2/play
            String transcodingUrl = getLiveTranscoding(fileId, flag);
            SpiderDebug.log("QuarkPersonal playerContent transcodingUrl=" + (transcodingUrl != null ? transcodingUrl.substring(0, Math.min(100, transcodingUrl.length())) : "null"));
            if (transcodingUrl == null || transcodingUrl.isEmpty()) {
                Notify.show("\u83b7\u53d6\u8f6c\u7801\u94fe\u63a5\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5Cookie\u662f\u5426\u8fc7\u671f");
                return Result.get().url("").string();
            }
            return Result.get().url(proxyVideoUrl(transcodingUrl, header)).octet().header(header).string();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        SpiderDebug.log("QuarkPersonal searchContent key=" + key);
        List<Vod> list = new ArrayList<>();

        if (cookie.isEmpty() || !cookie.contains("__pus")) {
            Notify.show("\u8bf7\u5148\u767b\u5f55\u5938\u514b\u7f51\u76d8");
            return Result.get().vod(list).page().string();
        }

        try {
            // 夸克搜索接口使用 GET 请求，参数通过 URL 传递
            String encodedKey = java.net.URLEncoder.encode(key, "UTF-8");
            String searchUrl = "https://drive-pc.quark.cn/1/clouddrive/file/search?pr=ucpro&fr=pc&uc_param_str="
                + "&pdir_fid=0"
                + "&_page=1"
                + "&_size=100"
                + "&_fetch_total=1"
                + "&_sort=file_type:asc,updated_at:desc"
                + "&q=" + encodedKey
                + "&query=" + encodedKey
                + "&search_range=all";
            Map<String, String> headers = getApiHeaders();
            SpiderDebug.log("QuarkPersonal search request url=" + searchUrl.substring(0, Math.min(150, searchUrl.length())));
            OkResult result = OkHttp.get(searchUrl, new HashMap<>(), headers);
            String resp = result.getBody();
            SpiderDebug.log("QuarkPersonal search response code=" + result.getCode() + " length=" + (resp != null ? resp.length() : 0));
            if (resp != null && resp.length() > 0) {
                SpiderDebug.log("QuarkPersonal search response preview=" + resp.substring(0, Math.min(200, resp.length())));
            }

            Map<String, Object> json = Json.parseSafe(resp, Map.class);
            SpiderDebug.log("QuarkPersonal search json parse=" + (json != null ? "ok" : "null"));
            if (json != null && json.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) json.get("data");
                List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("list");
                SpiderDebug.log("QuarkPersonal search items=" + (items != null ? items.size() : 0));
                if (items != null) {
                    int folderCount = 0;
                    int fileCount = 0;
                    for (Map<String, Object> item : items) {
                        Object fileTypeObj = item.get("file_type");
                        boolean isFolder = false;
                        if (fileTypeObj instanceof Number) {
                            isFolder = ((Number) fileTypeObj).intValue() == 0;
                        } else {
                            isFolder = "0".equals(String.valueOf(fileTypeObj));
                        }
                        if (isFolder) {
                            folderCount++;
                            String fid = String.valueOf(item.get("fid"));
                            String name = String.valueOf(item.get("file_name"));
                            String pdirFid = String.valueOf(item.get("pdir_fid"));
                            String path = buildPathFromFid(fid, name);

                            Vod vod = new Vod();
                            vod.setVodId(path);
                            vod.setVodName(name);
                            vod.setVodPic(defaultPic);
                            vod.setVodRemarks("\u641c\u7d22\u7ed3\u679c");
                            list.add(vod);
                        } else {
                            fileCount++;
                        }
                    }
                    SpiderDebug.log("QuarkPersonal search folders=" + folderCount + " files=" + fileCount + " result=" + list.size());
                }
            } else {
                SpiderDebug.log("QuarkPersonal search no data in response");
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal searchContent error: " + e.getMessage());
            Notify.show("\u641c\u7d22\u5931\u8d25: " + e.getMessage());
        }

        return Result.get().vod(list).page().string();
    }

    // ========== Cookie持久化 ==========

    private File getCookieFile() {
        return new File(savedContext.getFilesDir(), "quark_cookie.json");
    }

    private String readCookieFromFile() {
        try {
            File cacheFile = getCookieFile();
            if (cacheFile != null && cacheFile.exists()) {
                String content = new java.io.BufferedReader(new FileReader(cacheFile)).readLine();
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
            SpiderDebug.log("QuarkPersonal readCookieFromFile error: " + e.getMessage());
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
                java.io.FileWriter writer = new java.io.FileWriter(cacheFile);
                writer.write(cacheJson);
                writer.close();
                SpiderDebug.log("QuarkPersonal: cookie saved to file");
            }
        } catch (Exception e) {
            SpiderDebug.log("QuarkPersonal writeCookieToFile error: " + e.getMessage());
        }
    }

    // ========== 网盘API ==========

    private List<Folder> listFolders(String path) throws Exception {
        String fid = getFidByPath(path);
        String url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&pdir_fid=" + fid + "&_page=1&_size=200&_sort=file_type:asc,updated_at:desc";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);

        List<Folder> folders = new ArrayList<>();
        if (json != null && json.get("data") != null) {
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            if (list != null) {
                for (Map<String, Object> itemData : list) {
                    Object fileTypeObj = itemData.get("file_type");
                    boolean isFolder = false;
                    if (fileTypeObj instanceof Number) {
                        isFolder = ((Number) fileTypeObj).intValue() == 0;
                    } else {
                        isFolder = "0".equals(String.valueOf(fileTypeObj));
                    }
                    if (isFolder) {
                        Folder folder = new Folder();
                        folder.setFid(String.valueOf(itemData.get("fid")));
                        folder.setName(String.valueOf(itemData.get("file_name")));
                        folders.add(folder);
                    }
                }
            }
        }
        return folders;
    }

    private List<FileItem> listFileItems(String pathOrFid) throws Exception {
        String fid;
        if (pathOrFid.matches("\\d+") || pathOrFid.matches("[0-9a-f]{32}")) {
            fid = pathOrFid;
        } else {
            fid = getFidByPath(pathOrFid);
        }
        String url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&pdir_fid=" + fid + "&_page=1&_size=200&_sort=file_type:asc,updated_at:desc";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);

        List<FileItem> items = new ArrayList<>();
        if (json != null && json.get("data") != null) {
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            if (list != null) {
                for (Map<String, Object> itemData : list) {
                    FileItem item = new FileItem();
                    item.setFid(String.valueOf(itemData.get("fid")));
                    item.setName(String.valueOf(itemData.get("file_name")));
                    Object ft = itemData.get("file_type");
                    int fileType = 1;
                    if (ft instanceof Number) {
                        fileType = ((Number) ft).intValue();
                    } else {
                        fileType = Integer.parseInt(String.valueOf(ft));
                    }
                    item.setFileType(fileType);
                    Object sizeObj = itemData.get("size");
                    long size = 0;
                    if (sizeObj instanceof Number) {
                        size = ((Number) sizeObj).longValue();
                    }
                    item.setSize(size);
                    Object bigThumb = itemData.get("big_thumbnail");
                    if (bigThumb != null) {
                        item.setBigThumbnail(String.valueOf(bigThumb));
                    }
                    Object preview = itemData.get("preview_url");
                    if (preview != null) {
                        item.setPreviewUrl(String.valueOf(preview));
                    }
                    items.add(item);
                }
            }
        }
        return items;
    }

    private List<FileItem> listAllFileItems(String pathOrFid) throws Exception {
        String fid;
        if (pathOrFid.matches("\\d+") || pathOrFid.matches("[0-9a-f]{32}")) {
            fid = pathOrFid;
        } else {
            fid = getFidByPath(pathOrFid);
        }
        List<FileItem> allItems = new ArrayList<>();
        int _page = 1;
        boolean lastPage = false;

        while (!lastPage) {
            String url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&pdir_fid=" + fid + "&_page=" + _page + "&_size=200&_sort=file_type:asc,updated_at:desc";
            Map<String, String> headers = getApiHeaders();
            String result = OkHttp.string(url, new HashMap<>(), headers);
            Map<String, Object> json = Json.parseSafe(result, Map.class);

            if (json != null && json.get("data") != null) {
                Map<String, Object> data = (Map<String, Object>) json.get("data");
                List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
                if (list != null) {
                    for (Map<String, Object> itemData : list) {
                        FileItem item = new FileItem();
                        item.setFid(String.valueOf(itemData.get("fid")));
                        item.setName(String.valueOf(itemData.get("file_name")));
                        Object ft = itemData.get("file_type");
                        int fileType = 1;
                        if (ft instanceof Number) {
                            fileType = ((Number) ft).intValue();
                        } else {
                            fileType = Integer.parseInt(String.valueOf(ft));
                        }
                        item.setFileType(fileType);
                        Object sizeObj = itemData.get("size");
                        long size = 0;
                        if (sizeObj instanceof Number) {
                            size = ((Number) sizeObj).longValue();
                        }
                        item.setSize(size);
                        Object bigThumb = itemData.get("big_thumbnail");
                        if (bigThumb != null) {
                            item.setBigThumbnail(String.valueOf(bigThumb));
                        }
                        Object preview = itemData.get("preview_url");
                        if (preview != null) {
                            item.setPreviewUrl(String.valueOf(preview));
                        }
                        allItems.add(item);
                    }
                }
                // 检查是否最后一页
                Object lp = data.get("last_page");
                if (lp instanceof Boolean) {
                    lastPage = (Boolean) lp;
                } else {
                    lastPage = "true".equals(String.valueOf(lp));
                }
            } else {
                break;
            }
            _page++;
        }

        SpiderDebug.log("QuarkPersonal listAllFileItems fid=" + fid + " total=" + allItems.size() + " pages=" + _page);
        return allItems;
    }

    private String getFidByPath(String path) throws Exception {
        if (path == null || path.isEmpty() || path.equals("/")) return "0";

        String[] parts = path.split("/");
        String currentFid = "0";

        for (String part : parts) {
            if (part.isEmpty()) continue;
            String url = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&pdir_fid=" + currentFid + "&_page=1&_size=100&_sort=file_type:asc,updated_at:desc";
            Map<String, String> headers = getApiHeaders();
            String result = OkHttp.string(url, new HashMap<>(), headers);
            Map<String, Object> json = Json.parseSafe(result, Map.class);

            if (json == null || json.get("data") == null) {
                throw new Exception("Path not found: " + path);
            }

            Map<String, Object> data = (Map<String, Object>) json.get("data");
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
            boolean found = false;
            if (list != null) {
                for (Map<String, Object> item : list) {
                    if (part.equals(String.valueOf(item.get("file_name")))) {
                        currentFid = String.valueOf(item.get("fid"));
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                throw new Exception("Path not found: " + path);
            }
        }
        return currentFid;
    }

    private void refreshPus() {
        try {
            String refreshUrl = "https://drive-pc.quark.cn/1/clouddrive/file/sort?pr=ucpro&fr=pc&pdir_fid=0&_page=1&_size=1&_sort=file_type:asc,updated_at:desc";
            Map<String, String> refreshHeaders = getApiHeaders();
            OkResult refreshResult = OkHttp.get(refreshUrl, new HashMap<>(), refreshHeaders);
            List<String> setCookies = refreshResult.getResp().get("set-Cookie");
            if (setCookies != null && !setCookies.isEmpty()) {
                for (String c : setCookies) {
                    if (c.contains("__puus=")) {
                        String newPuus = c.split(";")[0];
                        if (cookie.contains("__puus=")) {
                            cookie = cookie.replaceAll("__puus=[^;]+", newPuus);
                        } else {
                            cookie = cookie + ";" + newPuus;
                        }
                        writeCookieToFile(cookie);
                        System.out.println("QuarkPersonal: __puus refreshed, cookie length=" + cookie.length());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("QuarkPersonal refresh __puus error: " + e.getMessage());
        }
    }

    private String getDownloadUrl(String fid) throws Exception {
        refreshPus();
        String url = "https://drive-pc.quark.cn/1/clouddrive/file/download?pr=ucpro&fr=pc&uc_param_str=";
        Map<String, String> headers = getApiHeaders();
        Map<String, Object> body = new HashMap<>();
        body.put("fids", Arrays.asList(fid));
        OkResult downloadResult = OkHttp.post(url, Json.toJson(body), headers);
        String result = downloadResult.getBody();
        SpiderDebug.log("QuarkPersonal getDownloadUrl response code=" + downloadResult.getCode() + " body=" + result.substring(0, Math.min(200, result.length())));
        Map<String, Object> json = Json.parseSafe(result, Map.class);

        if (json != null && json.get("data") != null) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) json.get("data");
            if (list != null && !list.isEmpty()) {
                Object downloadUrl = list.get(0).get("download_url");
                if (downloadUrl != null) return String.valueOf(downloadUrl);
            }
        }
        return null;
    }

    private Map<String, String> getApiHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch");
        headers.put("Referer", "https://pan.quark.cn/");
        headers.put("Content-Type", "application/json");
        headers.put("Host", "drive-pc.quark.cn");
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

    private String getBaseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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

    private String buildPathFromFid(String fid, String name) {
        return "fid:/" + fid + "/" + name;
    }



    private List<Vod.VodPlayBuilder.PlayUrl> buildPlayUrls(List<FileItem> videos) {
        List<Vod.VodPlayBuilder.PlayUrl> urls = new ArrayList<>();
        for (FileItem video : videos) {
            Vod.VodPlayBuilder.PlayUrl pu = new Vod.VodPlayBuilder.PlayUrl();
            pu.name = video.getName();
            pu.url = "+++++" + video.getFid();
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

    // ========== VIP检测和清晰度相关方法 ==========

    private void checkVip() throws Exception {
        String url = "https://drive-pc.quark.cn/1/clouddrive/member?pr=ucpro&fr=pc&uc_param_str=&fetch_subscribe=true&_ch=home&fetch_identity=true";
        Map<String, String> headers = getApiHeaders();
        String result = OkHttp.string(url, new HashMap<>(), headers);
        Map<String, Object> json = Json.parseSafe(result, Map.class);
        if (json != null && json.get("data") != null) {
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            Object memberType = data.get("member_type");
            isVip = memberType != null && String.valueOf(memberType).contains("VIP");
        }
        SpiderDebug.log("QuarkPersonal VIP check result: " + isVip);
    }

    private List<String> getPlayFormatList() {
        if (isVip) {
            return Arrays.asList("4K"/*, "超清", "高清", "普画"*/);
        } else {
            return Collections.singletonList("普画");
        }
    }

    private List<String> getPlayFormatQuarkList() {
        if (isVip) {
            return Arrays.asList("4k", "2k", "super", "high", "normal", "low");
        } else {
            return Collections.singletonList("low");
        }
    }

    private String getLiveTranscoding(String fid, String flag) throws Exception {
        refreshPus();
        String url = "https://drive-pc.quark.cn/1/clouddrive/file/v2/play?pr=ucpro&fr=pc";
        Map<String, String> headers = getApiHeaders();
        Map<String, Object> body = new HashMap<>();
        body.put("fid", fid);
        body.put("resolutions", "normal,low,high,super,2k,4k");
        body.put("supports", "fmp4");

        OkResult result = OkHttp.post(url, Json.toJson(body), headers);
        Map<String, Object> json = Json.parseSafe(result.getBody(), Map.class);

        if (json != null && json.get("data") != null) {
            Map<String, Object> data = (Map<String, Object>) json.get("data");
            List<Map<String, Object>> videoList = (List<Map<String, Object>>) data.get("video_list");
            if (videoList != null) {
                String flagId = flag.replace("夸克", "");
                int index = getPlayFormatList().indexOf(flagId);
                if (index >= 0) {
                    String quarkFormat = getPlayFormatQuarkList().get(index);
                    for (Map<String, Object> video : videoList) {
                        if (quarkFormat.equals(video.get("resolution"))) {
                            Map<String, Object> videoInfo = (Map<String, Object>) video.get("video_info");
                            return (String) videoInfo.get("url");
                        }
                    }
                }
            }
        }
        return null;
    }

    private String proxyVideoUrl(String url, Map<String, String> header) {
        return String.format(Proxy.getUrl() + "?do=quark&type=video&url=%s&header=%s",
            Util.base64Encode(url.getBytes(java.nio.charset.Charset.defaultCharset())),
            Util.base64Encode(Json.toJson(header).getBytes(java.nio.charset.Charset.defaultCharset())));
    }
}

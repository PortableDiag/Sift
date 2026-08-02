package com.sift.explorer.util;

import android.webkit.MimeTypeMap;

import com.sift.explorer.R;
import com.sift.explorer.fs.FileItem;

import java.util.HashSet;
import java.util.Set;

/** Maps file extensions to MIME types, broad categories and list icons. */
public class MimeUtils {

    public enum Category { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, PDF, DOC, CODE, APK, TEXT, FILE }

    private static final Set<String> IMAGE = set("jpg","jpeg","png","gif","bmp","webp","heic","heif","svg","ico","tiff","tif","raw","dng","cr2","nef");
    private static final Set<String> VIDEO = set("mp4","mkv","avi","mov","wmv","flv","webm","m4v","3gp","mpg","mpeg","ts","m2ts");
    private static final Set<String> AUDIO = set("mp3","wav","flac","aac","ogg","m4a","wma","opus","amr","mid","aiff");
    private static final Set<String> ARCHIVE = set("zip","rar","7z","tar","gz","bz2","xz","tgz","jar","apks","zst");
    private static final Set<String> CODE = set("java","kt","kts","c","cpp","h","hpp","cs","py","js","ts","jsx","tsx","html","htm","css","scss","json","xml","yml","yaml","sh","bash","go","rs","rb","php","swift","gradle","sql","toml","ini","conf");
    private static final Set<String> TEXT = set("txt","md","log","csv","tsv","rtf","tex","nfo","properties","env");
    private static final Set<String> DOC = set("doc","docx","odt","xls","xlsx","ods","ppt","pptx","odp","epub","mobi");

    public static Category categoryOf(FileItem item) {
        if (item.isDirectory) return Category.FOLDER;
        String e = item.getExtension();
        if (IMAGE.contains(e)) return Category.IMAGE;
        if (VIDEO.contains(e)) return Category.VIDEO;
        if (AUDIO.contains(e)) return Category.AUDIO;
        if (ARCHIVE.contains(e)) return Category.ARCHIVE;
        if (e.equals("pdf")) return Category.PDF;
        if (e.equals("apk")) return Category.APK;
        if (DOC.contains(e)) return Category.DOC;
        if (CODE.contains(e)) return Category.CODE;
        if (TEXT.contains(e)) return Category.TEXT;
        return Category.FILE;
    }

    public static int iconFor(FileItem item) {
        switch (categoryOf(item)) {
            case FOLDER:  return R.drawable.ic_folder;
            case IMAGE:   return R.drawable.ic_type_image;
            case VIDEO:   return R.drawable.ic_type_video;
            case AUDIO:   return R.drawable.ic_type_audio;
            case ARCHIVE: return R.drawable.ic_type_archive;
            case PDF:     return R.drawable.ic_type_pdf;
            case DOC:     return R.drawable.ic_type_doc;
            case CODE:    return R.drawable.ic_type_code;
            case APK:     return R.drawable.ic_type_apk;
            case TEXT:    return R.drawable.ic_type_text;
            default:      return R.drawable.ic_type_file;
        }
    }

    public static boolean isImage(FileItem item) { return !item.isDirectory && IMAGE.contains(item.getExtension()); }
    public static boolean isVideo(FileItem item) { return !item.isDirectory && VIDEO.contains(item.getExtension()); }
    public static boolean isTextLike(FileItem item) {
        String e = item.getExtension();
        return !item.isDirectory && (TEXT.contains(e) || CODE.contains(e));
    }

    public static String mimeType(FileItem item) {
        if (item.isDirectory) return "resource/folder";
        String ext = item.getExtension();
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) return mime;
        // Fill gaps MimeTypeMap misses so players' video/* filters still match.
        switch (ext) {
            case "ts": case "m2ts": case "mts": return "video/mp2t";
        }
        if (TEXT.contains(ext) || CODE.contains(ext)) return "text/plain";
        return "application/octet-stream";
    }

    private static Set<String> set(String... xs) {
        Set<String> s = new HashSet<>();
        for (String x : xs) s.add(x);
        return s;
    }
}

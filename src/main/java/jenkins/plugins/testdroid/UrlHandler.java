package jenkins.plugins.testdroid;

public class UrlHandler {
    public static String removeBewit(String url) {
        String bewitLessUrl = removeBewitIfFirstArgumentInUrl(url);
        bewitLessUrl = removeBewitIfNotFirstArgumentInUrl(bewitLessUrl);
        bewitLessUrl = bewitLessUrl.replaceAll("\\?&", "?");
        bewitLessUrl = bewitLessUrl.replaceAll("\\?$", "");
        return bewitLessUrl;
    }

    private static String removeBewitIfNotFirstArgumentInUrl(String url) {
        return url.replaceAll("&bewit=[^ &]+", "");
    }

    private static String removeBewitIfFirstArgumentInUrl(String url) {
        return url.replaceAll("\\?bewit=[^ &]+", "?");
    }
}

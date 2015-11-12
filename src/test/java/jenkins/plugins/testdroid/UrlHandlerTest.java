package jenkins.plugins.testdroid;

import org.junit.Test;

import static org.junit.Assert.*;

public class UrlHandlerTest {

    String defaultUrl = "https://domain-name.tld/resource";
    String defaultBewit = "bewit=my-bewit";
    String defaultParam = "oneParam=1";

    @Test
    public void test_removeBewit_should_return_the_string_as_is_there_is_no_argument() throws Exception {
        assertEquals(defaultUrl, UrlHandler.removeBewit(defaultUrl));
    }

    @Test
    public void test_removeBewit_should_return_the_string_as_is_if_there_is_an_argument_but_no_bewit() throws Exception {
        String baseUrl = defaultUrl + "?" + defaultParam;
        assertEquals(baseUrl, UrlHandler.removeBewit(baseUrl));
    }

    @Test
    public void test_removeBewit_should_remove_question_mark_when_bewit_is_the_only_argument() throws Exception {
        String baseUrl = defaultUrl + "?" + defaultBewit;
        assertEquals(defaultUrl, UrlHandler.removeBewit(baseUrl));
    }

    @Test
    public void test_removeBewit_should_leave_other_arguments_when_in_the_middle() throws Exception {
        String baseUrl = defaultUrl + "?" + defaultParam + "&" + defaultBewit + "&otherParam=2";
        assertEquals(defaultUrl + "?" + defaultParam + "&otherParam=2", UrlHandler.removeBewit(baseUrl));
    }

    @Test
    public void test_removeBewit_should_leave_the_question_mark_when_at_the_beginning() throws Exception {
        String baseUrl = defaultUrl + "?" + defaultBewit + "&" + defaultParam;
        assertEquals(defaultUrl + "?" + defaultParam, UrlHandler.removeBewit(baseUrl));
    }

    @Test
    public void test_removeBewit_should_remove_trailing_ampersand() throws Exception {
        String baseUrl = defaultUrl + "?" + defaultParam + "&" + defaultBewit;
        assertEquals(defaultUrl + "?" + defaultParam, UrlHandler.removeBewit(baseUrl));
    }
}

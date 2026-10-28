package com.example.shorturl.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import com.example.shorturl.service.ShortUrlService;

/**
 * Public short links: GET /{code} redirects to the original URL.
 * No CSRF token, cookie or session is involved, so links work from anywhere
 * (browser, curl, email, chat ...).
 */
@Controller
public class RedirectController {

    private final ShortUrlService shortUrlService;

    public RedirectController(ShortUrlService shortUrlService) {
        this.shortUrlService = shortUrlService;
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{3,30}}")
    public ModelAndView redirect(@PathVariable String code) {
        return shortUrlService.resolveAndTrackClick(code)
                .map(RedirectController::redirectTo)
                .orElseGet(() -> new ModelAndView("not-found", HttpStatus.NOT_FOUND));
    }

    private static ModelAndView redirectTo(String url) {
        RedirectView view = new RedirectView(url);
        // 302 (not 301) so browsers don't cache the redirect and every click is counted.
        view.setStatusCode(HttpStatus.FOUND);
        view.setExposeModelAttributes(false);
        return new ModelAndView(view);
    }
}

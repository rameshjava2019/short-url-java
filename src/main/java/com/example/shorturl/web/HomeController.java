package com.example.shorturl.web;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.shorturl.config.AppProperties;
import com.example.shorturl.service.ShortUrlException;
import com.example.shorturl.service.ShortUrlService;
import com.example.shorturl.service.ShortUrlView;

/**
 * The one-page UI: GET / shows the form, POST /shorten creates a link.
 * POST /shorten is protected by Spring Security's CSRF check (see SecurityConfig).
 */
@Controller
public class HomeController {

    private static final String RECENT_CODES = "recentCodes";
    private static final int RECENT_LIMIT = 10;

    private final ShortUrlService shortUrlService;
    private final CreateRateLimiter rateLimiter;
    private final AppProperties properties;

    public HomeController(ShortUrlService shortUrlService, CreateRateLimiter rateLimiter, AppProperties properties) {
        this.shortUrlService = shortUrlService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @GetMapping("/")
    public String home(Model model, HttpSession session) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CreateLinkForm());
        }
        return renderHome(model, session);
    }

    @PostMapping("/shorten")
    public String shorten(@Valid @ModelAttribute("form") CreateLinkForm form, BindingResult binding, Model model,
            HttpServletRequest request, HttpServletResponse response, HttpSession session, RedirectAttributes redirect) {

        if (!rateLimiter.tryAcquire(request.getRemoteAddr())) {
            return renderError(model, session, response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later.");
        }
        if (binding.hasErrors()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return renderHome(model, session);
        }

        try {
            ShortUrlView created = shortUrlService.create(form.getUrl(), form.getAlias());
            rememberInSession(session, created.code());
            // Post/Redirect/Get: a page refresh won't submit the form twice.
            redirect.addFlashAttribute("created", created);
            return "redirect:/";
        } catch (ShortUrlException e) {
            return renderError(model, session, response, e.getStatus(), e.getMessage());
        }
    }

    private String renderError(Model model, HttpSession session, HttpServletResponse response, HttpStatus status, String message) {
        response.setStatus(status.value());
        model.addAttribute("error", message);
        return renderHome(model, session);
    }

    private String renderHome(Model model, HttpSession session) {
        model.addAttribute("host", properties.host());
        model.addAttribute("recent", shortUrlService.findAll(recentCodes(session)));
        return "index";
    }

    // "Your recent links" lives in the user's session only: there are no accounts,
    // so a global list would expose everyone's links.
    private void rememberInSession(HttpSession session, String code) {
        List<String> codes = new ArrayList<>(recentCodes(session));
        codes.remove(code);
        codes.add(0, code);
        session.setAttribute(RECENT_CODES, new ArrayList<>(codes.subList(0, Math.min(codes.size(), RECENT_LIMIT))));
    }

    @SuppressWarnings("unchecked")
    private List<String> recentCodes(HttpSession session) {
        Object codes = session.getAttribute(RECENT_CODES);
        return codes instanceof List<?> ? (List<String>) codes : List.of();
    }
}

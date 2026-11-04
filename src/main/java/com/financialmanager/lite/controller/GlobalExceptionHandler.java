package com.financialmanager.lite.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.time.format.DateTimeParseException;

/**
 * Turns unexpected errors into a friendly message instead of the Whitelabel page.
 * Form submissions go back to the previous page with an alert; page loads render error.html.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ModelAndView handle(Exception ex, HttpServletRequest request, RedirectAttributes ra) throws Exception {
        // Let Spring handle status-carrying errors (404, 405, missing params...) as usual
        if (ex instanceof ErrorResponse) throw ex;

        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        String message = userMessage(ex);

        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            ra.addFlashAttribute("error", message);
            return new ModelAndView("redirect:" + backUrl(request));
        }

        ModelAndView mv = new ModelAndView("error");
        mv.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        mv.addObject("errorMessage", message);
        return mv;
    }

    private String userMessage(Exception ex) {
        if (ex instanceof DateTimeParseException) {
            return "Data inválida. Verifique as datas informadas e tente novamente.";
        }
        if (ex instanceof IllegalArgumentException && ex.getMessage() != null) {
            return ex.getMessage();
        }
        return "Ocorreu um erro inesperado. Tente novamente.";
    }

    /** Path of the page that submitted the form, never an external host. */
    private String backUrl(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null) return "/dashboard";
        try {
            URI uri = URI.create(referer);
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/")) return "/dashboard";
            return uri.getRawQuery() != null ? path + "?" + uri.getRawQuery() : path;
        } catch (IllegalArgumentException e) {
            return "/dashboard";
        }
    }
}

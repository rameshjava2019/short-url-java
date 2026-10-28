package com.example.shorturl.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The UI form. Detailed URL / alias rules are checked in the service. */
public class CreateLinkForm {

    @NotBlank(message = "Please provide a URL.")
    @Size(max = 2048, message = "URL must be at most 2048 characters.")
    private String url;

    @Size(max = 30, message = "Alias must be at most 30 characters.")
    private String alias;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }
}

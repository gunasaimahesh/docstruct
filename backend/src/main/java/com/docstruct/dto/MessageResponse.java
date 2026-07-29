package com.docstruct.dto;

public record MessageResponse(boolean success, String message) {

    public static MessageResponse of(String message) {
        return new MessageResponse(true, message);
    }
}

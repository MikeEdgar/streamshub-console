package com.github.eyefloaters.console.api.model;

abstract class DataResponse<T> {

    private final T data;

    protected DataResponse(T data) {
        this.data = data;
    }

    public T getData() {
        return data;
    }
}

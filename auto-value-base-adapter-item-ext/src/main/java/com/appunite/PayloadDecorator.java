package com.appunite;


public abstract class PayloadDecorator<T> {

    public abstract T currentValue();

    public abstract T previousValue();
}

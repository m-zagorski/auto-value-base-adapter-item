package com.appunite.auto_value_bai;


import com.appunite.AdapterId;
import com.appunite.rx.android.adapter.BaseAdapterItem;
import com.google.auto.value.AutoValue;

import javax.annotation.Nonnull;

@AutoValue
public abstract class BaseItem implements BaseAdapterItem {

    @Nonnull
    @AdapterId
    public abstract String id();

    public abstract int count();

    public abstract String field();

    @Nonnull
    public static BaseItem create(String id, int count, String field) {
        return new AutoValue_BaseItem(id, count, field);
    }
}

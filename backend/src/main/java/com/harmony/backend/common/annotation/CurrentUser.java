package com.harmony.backend.common.annotation;

import java.lang.annotation.*;

/**
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {

    /**
     */
    boolean required() default true;
}
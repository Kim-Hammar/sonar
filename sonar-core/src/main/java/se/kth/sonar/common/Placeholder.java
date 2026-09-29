package se.kth.sonar.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type whose contract is specified but whose behaviour is not implemented yet. Its
 * unimplemented members throw {@link NotImplemented}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Placeholder {}

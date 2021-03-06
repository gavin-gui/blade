package com.blade.mvc.handler;

import com.blade.exception.BladeException;
import com.blade.kit.*;
import com.blade.mvc.annotation.*;
import com.blade.mvc.hook.Signature;
import com.blade.mvc.http.HttpSession;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;
import com.blade.mvc.http.Session;
import com.blade.mvc.multipart.FileItem;
import com.blade.mvc.ui.ModelAndView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public final class MethodArgument {

    public static Object[] getArgs(Signature signature) throws Exception {
        Method   actionMethod = signature.getAction();
        Request  request      = signature.request();
        Response response     = signature.response();
        actionMethod.setAccessible(true);

        Parameter[] parameters     = actionMethod.getParameters();
        Object[]    args           = new Object[parameters.length];
        String[]    parameterNames = AsmKit.getMethodParamNames(actionMethod);

        for (int i = 0, len = parameters.length; i < len; i++) {
            Parameter parameter   = parameters[i];
            String    paramName   = parameterNames[i];
            int       annotations = parameter.getAnnotations().length;
            Class<?>  argType     = parameter.getType();
            if (annotations > 0) {
                QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
                if (null != queryParam) {
                    args[i] = getQueryParam(argType, queryParam, paramName, request);
                    continue;
                }
                BodyParam bodyParam = parameter.getAnnotation(BodyParam.class);
                if (null != bodyParam) {
                    args[i] = getBodyParam(argType, request);
                    continue;
                }
                PathParam pathParam = parameter.getAnnotation(PathParam.class);
                if (null != pathParam) {
                    args[i] = getPathParam(argType, pathParam, paramName, request);
                    continue;
                }
                HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
                if (null != headerParam) {
                    args[i] = getHeader(argType, headerParam, paramName, request);
                    continue;
                }
                // cookie param
                CookieParam cookieParam = parameter.getAnnotation(CookieParam.class);
                if (null != cookieParam) {
                    args[i] = getCookie(argType, cookieParam, paramName, request);
                    continue;
                }
                // form multipart
                MultipartParam multipartParam = parameter.getAnnotation(MultipartParam.class);
                if (null != multipartParam && argType == FileItem.class) {
                    String name = StringKit.isBlank(multipartParam.value()) ? paramName : multipartParam.value();
                    args[i] = request.fileItem(name).orElse(null);
                    continue;
                }
            }

            if (ReflectKit.isPrimitive(argType)) {
                args[i] = request.query(paramName);
            } else {
                if (argType == Signature.class) {
                    args[i] = signature;
                    continue;
                } else if (argType == Request.class) {
                    args[i] = request;
                    continue;
                } else if (argType == Response.class) {
                    args[i] = response;
                    continue;
                } else if (argType == Session.class || argType == HttpSession.class) {
                    args[i] = request.session();
                    continue;
                } else if (argType == FileItem.class) {
                    args[i] = new ArrayList<>(request.fileItems().values()).get(0);
                    continue;
                } else if (argType == ModelAndView.class) {
                    args[i] = new ModelAndView();
                    continue;
                } else if (argType == Map.class) {
                    args[i] = request.parameters();
                    continue;
                } else {
                    args[i] = parseModel(argType, request, null);
                    continue;
                }
            }
        }
        return args;
    }

    private static Object getBodyParam(Class<?> argType, Request request) throws BladeException {
        if (ReflectKit.isPrimitive(argType)) {
            return ReflectKit.convert(argType, request.bodyToString());
        } else {
            String json = request.bodyToString();
            return StringKit.isNotBlank(json) ? JsonKit.formJson(request.bodyToString(), argType) : null;
        }
    }

    private static Object getQueryParam(Class<?> argType, QueryParam queryParam, String paramName, Request request) throws BladeException {
        String name = StringKit.isBlank(queryParam.name()) ? paramName : queryParam.name();

        if (ReflectKit.isPrimitive(argType)) {
            Optional<String> val      = request.query(name);
            boolean          required = queryParam.required();
            if (!val.isPresent()) {
                val = Optional.of(queryParam.defaultValue());
            }
            if (required && !val.isPresent()) {
                Assert.throwException(String.format("query param [%s] not is empty.", paramName));
            }
            return getRequestParam(argType, val.get());
        } else {
            return parseModel(argType, request, name);
        }
    }

    private static Object getCookie(Class<?> argType, CookieParam cookieParam, String paramName, Request request) throws BladeException {
        String           cookieName = StringKit.isBlank(cookieParam.value()) ? paramName : cookieParam.value();
        Optional<String> val        = request.cookie(cookieName);
        boolean          required   = cookieParam.required();
        if (!val.isPresent()) {
            val = Optional.of(cookieParam.defaultValue());
        }
        if (required && !val.isPresent()) {
            Assert.throwException(String.format("cookie param [%s] not is empty.", paramName));
        }
        return getRequestParam(argType, val.get());
    }

    private static Object getHeader(Class<?> argType, HeaderParam headerParam, String paramName, Request request) throws BladeException {
        String  key      = StringKit.isBlank(headerParam.value()) ? paramName : headerParam.value();
        String  val      = request.header(key);
        boolean required = headerParam.required();
        if (StringKit.isBlank(val)) {
            val = headerParam.defaultValue();
        }
        if (required && StringKit.isBlank(val)) {
            Assert.throwException(String.format("header param [%s] not is empty.", paramName));
        }
        return getRequestParam(argType, val);
    }

    private static Object getPathParam(Class<?> argType, PathParam pathParam, String paramName, Request request) {
        String name = StringKit.isBlank(pathParam.name()) ? paramName : pathParam.name();
        String val  = request.pathString(name);
        if (StringKit.isBlank(val)) {
            val = pathParam.defaultValue();
        }
        return getRequestParam(argType, val);
    }

    private static Object parseModel(Class<?> argType, Request request, String name) throws BladeException {
        try {
            Field[] fields = argType.getDeclaredFields();
            if (null == fields || fields.length == 0) {
                return null;
            }
            Object  obj      = ReflectKit.newInstance(argType);
            boolean hasField = false;

            for (Field field : fields) {
                field.setAccessible(true);
                if (field.getName().equals("serialVersionUID")) {
                    continue;
                }
                Optional<String> fieldValue = request.query(field.getName());
                if (null != name) {
                    String fieldName = name + "[" + field.getName() + "]";
                    fieldValue = request.query(fieldName);
                }
                if (fieldValue.isPresent() && StringKit.isNotBlank(fieldValue.get())) {
                    Object value = ReflectKit.convert(field.getType(), fieldValue.get());
                    field.set(obj, value);
                    hasField = true;
                }
            }
            return hasField ? obj : null;
        } catch (Exception e) {
            throw new BladeException(e);
        }
    }

    public static Object getRequestParam(Class<?> parameterType, String val) {
        Object result = null;
        if (parameterType.equals(String.class)) {
            return val;
        }
        if (StringKit.isBlank(val)) {
            if (parameterType.equals(int.class) || parameterType.equals(double.class) ||
                    parameterType.equals(long.class) || parameterType.equals(byte.class) || parameterType.equals(float.class)) {
                result = 0;
            }
            if (parameterType.equals(boolean.class)) {
                result = false;
            }
        } else {
            if (parameterType.equals(Integer.class) || parameterType.equals(int.class)) {
                result = Integer.parseInt(val);
            }
            if (parameterType.equals(Long.class) || parameterType.equals(long.class)) {
                result = Long.parseLong(val);
            }
            if (parameterType.equals(Double.class) || parameterType.equals(double.class)) {
                result = Double.parseDouble(val);
            }
            if (parameterType.equals(Float.class) || parameterType.equals(float.class)) {
                result = Float.parseFloat(val);
            }
            if (parameterType.equals(Boolean.class) || parameterType.equals(boolean.class)) {
                result = Boolean.parseBoolean(val);
            }
            if (parameterType.equals(Byte.class) || parameterType.equals(byte.class)) {
                result = Byte.parseByte(val);
            }
        }
        return result;
    }

}
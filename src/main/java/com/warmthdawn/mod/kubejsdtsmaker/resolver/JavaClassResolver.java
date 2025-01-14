package com.warmthdawn.mod.kubejsdtsmaker.resolver;

import com.mojang.serialization.codecs.SimpleMapCodec;
import com.mojang.serialization.codecs.UnboundedMapCodec;
import com.warmthdawn.mod.kubejsdtsmaker.context.ResolveContext;
import com.warmthdawn.mod.kubejsdtsmaker.java.*;
import com.warmthdawn.mod.kubejsdtsmaker.typescript.misc.CallSignature;
import com.warmthdawn.mod.kubejsdtsmaker.util.PropertySignature;
import com.warmthdawn.mod.kubejsdtsmaker.util.GenericUtils;
import com.warmthdawn.mod.kubejsdtsmaker.util.RhinoUtils;
import dev.latvian.mods.rhino.util.HideFromJS;
import dev.latvian.mods.rhino.util.RemapForJS;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.*;
import java.util.*;

public class JavaClassResolver {
    private final Logger logger = LogManager.getLogger();

    @Nonnull
    private final ResolveContext context;

    private final Set<Class<?>> resolvingClass = new HashSet<>();

    public JavaClassResolver(@Nonnull ResolveContext context) {
        this.context = context;
    }


    public static void resolve(Collection<Class<?>> classes, ResolveContext context, int iterCount) {

        Collection<Class<?>> classesToResolve = classes;
        for (int i = 0; i <= iterCount; i++) {
            if (classesToResolve.isEmpty()) {
                break;
            }
            JavaClassResolver resolver = new JavaClassResolver(context);
            for (Class<?> clazz : classesToResolve) {
                resolver.resolve(clazz);
            }
            classesToResolve = resolver.relevantClasses;
        }

    }

    public JavaTypeInfo resolve(@Nullable Class<?> clazz) {
        if (clazz == null || clazz.isArray() || clazz.isPrimitive()) {
            return null;
        }
        if (clazz.isAnonymousClass() || clazz.isLocalClass() || !Modifier.isPublic(clazz.getModifiers())) {
            return null;
        }
        if (context.getBlacklist().isBlacklisted(clazz)) {
            return null;
        }
        if (context.isResolved(clazz)) {
            return context.get(clazz);
        }
        if (resolvingClass.contains(clazz)) {
            logger.error("trying to resolve a resolving class, there might be an error!");
            return null;
        }
        resolvingClass.add(clazz);
        relevantClasses.remove(clazz);

        for (Type genericInterface : clazz.getGenericInterfaces()) {
            addRelevantByGenericArgs(genericInterface);
        }
        Type genericSuperclass = clazz.getGenericSuperclass();
        addRelevantByGenericArgs(genericSuperclass);

        for (TypeVariable<? extends Class<?>> typeParameter : clazz.getTypeParameters()) {
            addRelevantByType(typeParameter);
        }

//        if (clazz.isMemberClass()) {
//            Class<?> declaringClass = clazz.getDeclaringClass();
//            if (!resolvingClass.contains(declaringClass) && !context.isResolved(declaringClass)) {
//                resolve(declaringClass);
//            }
//        }
        //内部类暂时不主动解析
//        Class<?>[] innerClasses = clazz.getDeclaredClasses();
//        for (Class<?> inner : innerClasses) {
//            addRelevant(inner);
//        }

        if (clazz.isMemberClass()) {
            if (clazz.getDeclaringClass().isMemberClass()) {
                //禁止套娃
                return null;
            }
        }


        HashSet<String> memberKeys = new HashSet<>();
        if (!clazz.isInterface()) {
            Class<?> superclass = clazz.getSuperclass();
            JavaTypeInfo resolve = resolve(superclass);
            if (resolve != null)
                memberKeys.addAll(resolve.getMemberKeys());
        }
        for (Class<?> clazzInterface : clazz.getInterfaces()) {
            JavaTypeInfo resolve = resolve(clazzInterface);
            if (resolve != null)
                memberKeys.addAll(resolve.getMemberKeys());
        }
        JavaTypeInfo resolved = null;
        try {
            resolved = doResolve(clazz, memberKeys);
            context.add(clazz, resolved);
        } catch (NoClassDefFoundError error) {
            logger.error("Could not find load class {}", error.getMessage());
        }
        resolvingClass.remove(clazz);
        return resolved;
    }

    private final Set<Class<?>> relevantClasses = new HashSet<>();

    public Set<Class<?>> getRelevantClasses() {
        return Collections.unmodifiableSet(relevantClasses);
    }

    private void addRelevant(Class<?> clazz) {
        if (context.isResolved(clazz)) {
            return;
        }
        if (resolvingClass.contains(clazz)) {
            return;
        }
        relevantClasses.add(clazz);
    }

    private void addRelevant(Field field) {
        Class<?> type = field.getType();
        addRelevant(type);
    }

    private void addRelevant(Constructor<?> constructor) {
        for (Type parameterType : constructor.getGenericParameterTypes()) {
            addRelevantByType(parameterType);
        }
    }

    private void addRelevant(Method method) {
        addRelevant(method.getReturnType());
        for (TypeVariable<Method> typeParameter : method.getTypeParameters()) {
            addRelevantByType(typeParameter);
        }
        for (Type parameterType : method.getGenericParameterTypes()) {
            addRelevantByType(parameterType);
        }
    }

    private void addRelevantByGenericArgs(Type type) {
        if (type instanceof ParameterizedType) {
            for (Type actualTypeArgument : ((ParameterizedType) type).getActualTypeArguments()) {
                addRelevantByType(type);
            }
        }
    }

    private void addRelevantByType(Type type) {
        HashSet<Class<?>> set = new HashSet<>();
        GenericUtils.findRelativeClass(type, set);
        for (Class<?> clazz : set) {
            addRelevant(clazz);
        }
    }


    private String remapMemberName(Member member) {
        if (member instanceof AnnotatedElement) {
            if (((AnnotatedElement) member).isAnnotationPresent(HideFromJS.class)) {
                return null;
            }
            RemapForJS remap = ((AnnotatedElement) member).getAnnotation(RemapForJS.class);
            if (remap != null) {
                return remap.value();
            }
        }
        return member.getName();
    }

    private JavaTypeInfo doResolve(Class<?> clazz, HashSet<String> memberKeys) {


        Map<String, JavaInstanceMember> members = new HashMap<>();
        Map<String, JavaStaticMember> staticMembers = new HashMap<>();

        doResolveCommons(clazz, members, staticMembers);
        JavaConstructorMember constructorMember = null;
        if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) {
            constructorMember = doResolveConstructors(clazz);
        }

        JavaTypeInfo result = new JavaTypeInfo(clazz, members, staticMembers, constructorMember, memberKeys);
        //解析方法重写


        HashSet<String> visited = new HashSet<>();
        Iterator<Map.Entry<String, JavaInstanceMember>> iterator = members.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, JavaInstanceMember> entry = iterator.next();
            String name = entry.getKey();
            visited.add(name);
            List<JavaInstanceMember> inheritedMembers = result.findInheritedMembers(context, name);
            boolean notEmpty = entry.getValue().resolveOverride(context.getBlacklist(), clazz, inheritedMembers);
            if (!notEmpty) {
                iterator.remove();
            }
        }
        HashSet<String> otherKeys = new HashSet<>(memberKeys);
        otherKeys.removeAll(visited);


        for (String memberKey : otherKeys) {
            List<JavaInstanceMember> inheritedMembers = result.findInheritedMembers(context, memberKey);
            JavaInstanceMember member = new JavaInstanceMember(memberKey);
            boolean notEmpty = member.resolveOverride(context.getBlacklist(), clazz, inheritedMembers);
            if (notEmpty) {
                members.put(memberKey, member);
            }
        }

        return result;

    }

    private JavaConstructorMember doResolveConstructors(Class<?> clazz) {
        List<Constructor<?>> memberList = new ArrayList<>();
        Constructor<?>[] constructors = clazz.getConstructors();
        for (Constructor<?> constructor : constructors) {
            memberList.add(constructor);
            addRelevant(constructor);
        }
        if (memberList.isEmpty()) {
            return null;
        }
        return new JavaConstructorMember(memberList);
    }

    private void doResolveCommons(Class<?> clazz, Map<String, JavaInstanceMember> members, Map<String, JavaStaticMember> staticMembers) {
        //收集成员
        Method[] methods = clazz.getDeclaredMethods();
        Field[] fields = clazz.getDeclaredFields();

        List<Method> staticMethods = new ArrayList<>();
        List<Method> instanceMethods = new ArrayList<>();
        for (Method method : methods) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || method.isBridge()) {
                continue;
            }
            String name = remapMemberName(method);
            if (name == null) {
                continue;
            }
            if (Modifier.isStatic(modifiers)) {
                JavaStaticMember member = staticMembers.computeIfAbsent(name, JavaStaticMember::new);
                staticMethods.add(method);
                member.addMethod(method);
            } else {
                JavaInstanceMember member = members.computeIfAbsent(name, JavaInstanceMember::new);
                instanceMethods.add(method);
                member.addMethod(method);
            }
            addRelevant(method);
        }
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isTransient(modifiers)) {
                continue;
            }

            String name = remapMemberName(field);
            if (Modifier.isStatic(modifiers)) {
                JavaStaticMember member = staticMembers.computeIfAbsent(name, JavaStaticMember::new);
                member.addField(field);
            } else {
                JavaInstanceMember member = members.computeIfAbsent(name, JavaInstanceMember::new);
                member.addField(field);
            }
            addRelevant(field);
        }
        for (PropertySignature bean : RhinoUtils.getBeans(staticMethods)) {
            JavaStaticMember member = staticMembers.computeIfAbsent(bean.getName(), JavaStaticMember::new);
            member.addBean(bean);
        }
        for (PropertySignature bean : RhinoUtils.getBeans(instanceMethods)) {
            JavaInstanceMember member = members.computeIfAbsent(bean.getName(), JavaInstanceMember::new);
            member.addBean(bean);
        }

    }
}

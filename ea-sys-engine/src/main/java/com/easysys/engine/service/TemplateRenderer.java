package com.easysys.engine.service;

import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Map;

/**
 * 模板渲染：FreeMarker（2.3.x），模板正文存 DB、运行时以 StringReader 解析。
 * RETHROW_HANDLER：渲染失败（变量缺失/语法错）抛 TemplateException，
 * 由执行器按成员记为 FAILED 下发记录，不影响整批执行。
 */
@Service
public class TemplateRenderer {

    private final Configuration freemarker;

    public TemplateRenderer() {
        this.freemarker = new Configuration(Configuration.VERSION_2_3_33);
        freemarker.setDefaultEncoding("UTF-8");
        freemarker.setLocale(Locale.CHINA);
        freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        freemarker.setLogTemplateExceptions(false);
        freemarker.setWrapUncheckedExceptions(true);
        // DefaultObjectWrapper：Map 键支持 .phone / ["phone"] 命名访问（contact.* 展开的画像键）
        freemarker.setObjectWrapper(new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_33).build());
    }

    /** 渲染模板正文；context = 成员画像（contact 直属列 + attributes 展开 + tags）。 */
    public String render(String source, Map<String, Object> context) throws IOException, TemplateException {
        Template template = new Template("inline", new StringReader(source), freemarker);
        StringWriter out = new StringWriter();
        template.process(context, out);
        return out.toString();
    }
}
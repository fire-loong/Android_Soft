package com.actionloop.api.ai;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感通知过滤（指导书 4.5）：验证码/支付/密码/证件默认不进入云端 AI，也不生成提醒。
 * 命中后仅保留原文（本地/私有），由用户手动处理。
 */
@Component
public class SensitiveContentDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("验证码|校验码|动态码|短信码|一次性密码|OTP", Pattern.CASE_INSENSITIVE),
            Pattern.compile("密码|口令|password|passcode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("支付|付款|转账|汇款|银行卡|信用卡|余额|扣款", Pattern.CASE_INSENSITIVE),
            Pattern.compile("身份证|护照号|社保号|银行卡号|CVV|有效期\\s*\\d{2}/\\d{2}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("中奖|退款链接|点击链接领取|扫码领", Pattern.CASE_INSENSITIVE)
    );

    public record Detection(boolean sensitive, String reason) {
    }

    public Detection detect(String text) {
        if (text == null || text.isBlank()) {
            return new Detection(false, null);
        }
        for (Pattern p : PATTERNS) {
            var m = p.matcher(text);
            if (m.find()) {
                return new Detection(true, "SENSITIVE:" + m.group());
            }
        }
        return new Detection(false, null);
    }
}

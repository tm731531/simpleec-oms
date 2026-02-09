package com.simpleec.common.util;

public final class PiiMasker {

    private PiiMasker() {}

    /**
     * 王小明 → 王*明, John Smith → J*********h
     */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() == 1) return "*";
        if (name.length() == 2) return name.charAt(0) + "*";
        return name.charAt(0)
                + "*".repeat(name.length() - 2)
                + name.charAt(name.length() - 1);
    }

    /**
     * 0912345678 → 0912***678 (keep first 4, last 3)
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) return phone;
        if (phone.length() <= 4) return "*".repeat(phone.length());
        if (phone.length() <= 7) {
            return phone.substring(0, 2)
                    + "*".repeat(phone.length() - 4)
                    + phone.substring(phone.length() - 2);
        }
        return phone.substring(0, 4)
                + "*".repeat(phone.length() - 7)
                + phone.substring(phone.length() - 3);
    }

    /**
     * tommy@gmail.com → to***@gmail.com (keep first 2 + @domain)
     */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) return email;
        int at = email.indexOf('@');
        if (at < 0) return maskName(email);
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) return local + "***" + domain;
        return local.substring(0, 2) + "***" + domain;
    }

    /**
     * 台北市信義區xxx → 台北市*** (keep first 3 chars = city)
     */
    public static String maskAddress(String address) {
        if (address == null || address.isEmpty()) return address;
        if (address.length() <= 3) return address;
        return address.substring(0, 3) + "***";
    }
}

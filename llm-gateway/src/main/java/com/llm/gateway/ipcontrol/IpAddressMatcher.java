package com.llm.gateway.ipcontrol;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** IP 字面量/CIDR 解析与匹配；只接受数字地址，绝不在请求链路做 DNS 查询。 */
final class IpAddressMatcher {

    private final byte[] network;
    private final int prefixLength;
    private final String expression;

    private IpAddressMatcher(byte[] network, int prefixLength, String expression) {
        this.network = network;
        this.prefixLength = prefixLength;
        this.expression = expression;
    }

    static IpAddressMatcher parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IpControlValidationException("IP/CIDR 不能为空");
        }
        String candidate = value.trim();
        int slash = candidate.indexOf('/');
        if (slash != candidate.lastIndexOf('/')) {
            throw new IpControlValidationException("非法 CIDR：" + value);
        }
        String literal = slash >= 0 ? candidate.substring(0, slash) : candidate;
        byte[] address = parseLiteral(literal, value);
        int maxBits = address.length * 8;
        int prefix = maxBits;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(candidate.substring(slash + 1));
            } catch (NumberFormatException ex) {
                throw new IpControlValidationException("非法 CIDR 前缀：" + value);
            }
            if (prefix < 0 || prefix > maxBits) {
                throw new IpControlValidationException("CIDR 前缀超出范围：" + value);
            }
        }

        byte[] network = mask(address, prefix);
        String canonical = hostAddress(network);
        String normalized = prefix == maxBits ? canonical : canonical + "/" + prefix;
        return new IpAddressMatcher(network, prefix, normalized);
    }

    static String normalizeAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return hostAddress(parseLiteral(value, value));
        } catch (IpControlValidationException ignored) {
            return null;
        }
    }

    boolean matches(String normalizedAddress) {
        if (normalizedAddress == null) {
            return false;
        }
        byte[] address;
        try {
            address = parseLiteral(normalizedAddress, normalizedAddress);
        } catch (IpControlValidationException ignored) {
            return false;
        }
        return address.length == network.length && Arrays.equals(mask(address, prefixLength), network);
    }

    String expression() {
        return expression;
    }

    private static byte[] parseLiteral(String value, String original) {
        String literal = value == null ? "" : value.trim();
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if ((!literal.contains(".") && !literal.contains(":")) || !literal.matches("[0-9a-fA-F:.]+")) {
            throw new IpControlValidationException("非法 IP 地址：" + original);
        }
        if (literal.contains(".") && !literal.contains(":")) {
            return parseIpv4(literal, original);
        }
        try {
            byte[] address = InetAddress.getByName(literal).getAddress();
            if (address.length != 4 && address.length != 16) {
                throw new IpControlValidationException("非法 IP 地址：" + original);
            }
            return address;
        } catch (UnknownHostException ex) {
            throw new IpControlValidationException("非法 IP 地址：" + original);
        }
    }

    private static byte[] parseIpv4(String literal, String original) {
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) {
            throw new IpControlValidationException("非法 IPv4 地址：" + original);
        }
        byte[] address = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                throw new IpControlValidationException("非法 IPv4 地址：" + original);
            }
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                throw new IpControlValidationException("非法 IPv4 地址：" + original);
            }
            if (octet > 255) {
                throw new IpControlValidationException("非法 IPv4 地址：" + original);
            }
            address[i] = (byte) octet;
        }
        return address;
    }

    private static byte[] mask(byte[] address, int prefixLength) {
        byte[] result = address.clone();
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        if (remainingBits > 0) {
            result[fullBytes] = (byte) (result[fullBytes] & (0xFF << (8 - remainingBits)));
            fullBytes++;
        }
        Arrays.fill(result, fullBytes, result.length, (byte) 0);
        return result;
    }

    private static String hostAddress(byte[] address) {
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException ex) {
            throw new IllegalStateException("已解析的 IP 字节长度非法", ex);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof IpAddressMatcher matcher && expression.equals(matcher.expression));
    }

    @Override
    public int hashCode() {
        return expression.hashCode();
    }
}

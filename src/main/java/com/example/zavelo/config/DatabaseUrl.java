package com.example.zavelo.config;

import java.net.URI;
import java.net.URISyntaxException;

/** Turns a database address like postgresql://user:pass@host/db into the three values Spring needs. */
final class DatabaseUrl {

    final String jdbcUrl;
    final String username;
    final String password;

    private DatabaseUrl(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    static DatabaseUrl parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("jdbc:")) return new DatabaseUrl(text, null, null);

        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("DATABASE_URL is not a valid address.", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("postgres") || scheme.equals("postgresql")))
            throw new IllegalArgumentException("DATABASE_URL must start with postgresql://");
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null || path.length() <= 1)
            throw new IllegalArgumentException("DATABASE_URL must look like postgresql://user:password@host/database");

        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String user = null, pass = null;
        String info = uri.getUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            user = colon < 0 ? info : info.substring(0, colon);
            pass = colon < 0 ? null : info.substring(colon + 1);
        }
        // Render's outside address (it contains dots) needs an encrypted connection; the inside one does not.
        String query = uri.getQuery() != null ? "?" + uri.getQuery() : (host.contains(".") ? "?sslmode=require" : "");
        return new DatabaseUrl("jdbc:postgresql://" + host + ":" + port + path + query, user, pass);
    }
}

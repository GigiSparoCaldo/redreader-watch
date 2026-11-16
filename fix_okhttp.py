p = "src/main/java/org/quantumbadger/redreader/http/okhttp/OKHTTPBackend.kt"
s = open(p).read()

s = s.replace(
    "import org.quantumbadger.redreader.common.UriString",
    "import org.quantumbadger.redreader.common.UriString\nimport org.quantumbadger.redreader.common.WatchHelper"
)

url_patch = """var u = details.url.value
        val ck = WatchHelper.getCookie(context)
        if (u.contains("oauth.reddit.com") || u.contains("reddit.com")) {
            u = u.replace("https://oauth.reddit.com", "https://old.reddit.com")
            u = u.replace("https://www.reddit.com", "https://old.reddit.com")
            u = u.replace("/.json", ".json")
            val qi = u.indexOf("?")
            val base = (if (qi >= 0) u.substring(0, qi) else u).trimEnd('/')
            var qry = if (qi >= 0) u.substring(qi) else ""
            if (base == "https://old.reddit.com") {
                u = "https://old.reddit.com/.json$qry"
            } else if (!base.endsWith(".json") && !base.contains("/api/")) {
                u = "$base.json$qry"
            } else {
                u = "$base$qry"
            }
            if (!u.contains("raw_json=")) {
                u += if (u.contains("?")) "&raw_json=1" else "?raw_json=1"
            }
            reqBuilder.header("Accept", "application/json")
            reqBuilder.header("User-Agent", "android:org.quantumbadger.redreader.watch:v1.27 (by /u/wristreader)")
            if (ck.isNotEmpty()) {
                reqBuilder.header("Cookie", ck)
                val uh = WatchHelper.getModhash(context)
                if (requestBody != null && uh.isNotEmpty()) reqBuilder.header("X-Modhash", uh)
            }
        }
        reqBuilder.url(u)"""

assert "reqBuilder.url(details.url.value)" in s
s = s.replace("reqBuilder.url(details.url.value)", url_patch, 1)
s = s.replace(
    "reqBuilder.addHeader(name, value)",
    "if (name.equals(\"Authorization\", true) && (value.contains(\"COOKIE_SESSION\") || value.contains(\"ANON_PUBLIC\"))) return\n                reqBuilder.addHeader(name, value)"
)

open(p, "w").write(s)
print("OK 2/4 OKHTTPBackend")

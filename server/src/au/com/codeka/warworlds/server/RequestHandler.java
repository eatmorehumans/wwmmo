package au.com.codeka.warworlds.server;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;

import javax.servlet.ServletInputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import jregex.Matcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.codeka.common.protoformat.PbFormatter;
import au.com.codeka.warworlds.server.data.DB;
import au.com.codeka.warworlds.server.data.SqlStmt;

import com.google.protobuf.Message;

/**
 * This is the base class for the game's request handlers. It handles some common tasks such as
 * extracting protocol buffers from the request body, and so on.
 */
public class RequestHandler {
    private final Logger log = LoggerFactory.getLogger(RequestHandler.class);
    private HttpServletRequest mRequest;
    private HttpServletResponse mResponse;
    private Matcher mRouteMatcher;
    private Session mSession;
    private String mExtraOption;

    protected String getUrlParameter(String name) {
        try {
            if (mRouteMatcher.isCaptured(name)) {
                return mRouteMatcher.group(name);
            }
        } catch(IllegalArgumentException e) {
        }
        return "";
    }

    protected String getRealm() {
        return getUrlParameter("realm");
    }

    protected String getExtraOption() {
        return mExtraOption;
    }

    public void handle(Matcher matcher, String extraOption, HttpServletRequest request,
                       HttpServletResponse response) {
        mRequest = request;
        mResponse = response;
        mRouteMatcher = matcher;
        mExtraOption = extraOption;

        // start off with status 200, but the handler might change it
        mResponse.setStatus(200);

        try {
            if (request.getMethod().equals("GET")) {
                get();
            } else if (request.getMethod().equals("POST")) {
                post();
            } else if (request.getMethod().equals("PUT")) {
                put();
            } else if (request.getMethod().equals("DELETE")) {
                delete();
            } else {
                throw new RequestException(501);
            }
        } catch(RequestException e) {
            log.error("Unhandled error in URL: "+request.getRequestURI(), e);
            e.populate(mResponse);
            setResponseBody(e.getGenericError());
            return;
        } catch(Exception e) {
            log.error("Unhandled error!", e);
            mResponse.setStatus(500);
            return;
        }
    }

    protected void get() throws RequestException {
        throw new RequestException(501);
    }

    protected void put() throws RequestException {
        throw new RequestException(501);
    }

    protected void post() throws RequestException {
        throw new RequestException(501);
    }

    protected void delete() throws RequestException {
        throw new RequestException(501);
    }

    protected void setResponseBody(Message pb) {
        if (pb == null) {
            return;
        }

        for (String acceptValue : mRequest.getHeader("Accept").split(",")) {
            if (acceptValue.startsWith("text/")) {
                setResponseBodyText(pb);
                return;
            } else if (acceptValue.startsWith("application/json")) {
                setResponseBodyJson(pb);
                return;
            }
        }

        mResponse.setContentType("application/x-protobuf");
        mResponse.setHeader("Content-Type", "application/x-protobuf");
        try {
            pb.writeTo(mResponse.getOutputStream());
        } catch (IOException e) {
        }
    }

    private void setResponseBodyText(Message pb) {
        mResponse.setContentType("text/plain");
        mResponse.setHeader("Content-Type", "text/plain");
        try {
            mResponse.getWriter().write(PbFormatter.toJson(pb, true));
        } catch (IOException e) {
        }
    }

    private void setResponseBodyJson(Message pb) {
        mResponse.setContentType("application/json");
        mResponse.setHeader("Content-Type", "application/json");
        try {
            mResponse.getWriter().write(PbFormatter.toJson(pb));
        } catch (IOException e) {
        }
    }

    protected HttpServletRequest getRequest() {
        return mRequest;
    }
    protected HttpServletResponse getResponse() {
        return mResponse;
    }

    protected Session getSession() throws RequestException {
        return getSession(true);
    }

    protected Session getSession(boolean errorOnNotAuth) throws RequestException {
        if (mSession == null) {
            String sessionCookieValue = "";
            for (Cookie cookie : mRequest.getCookies()) {
                if (cookie.getName().equals("SESSION")) {
                    sessionCookieValue = cookie.getValue();

                    // TODO: cache these!
                    try (SqlStmt stmt = DB.prepare("SELECT * FROM sessions WHERE session_cookie=?")) {
                        stmt.setString(1, sessionCookieValue);
                        ResultSet rs = stmt.select();
                        if (rs.next()) {
                            mSession = new Session(rs);
                        }
                    } catch (Exception e) {
                        throw new RequestException(e);
                    }
                }
            }

            if (mSession == null && errorOnNotAuth) {
                throw new RequestException(403, "Could not find session, session cookie: "+sessionCookieValue);
            }
        }

        return mSession;
    }

    @SuppressWarnings({"unchecked"})
    protected <T> T getRequestBody(Class<T> protoBuffFactory) {
        T result = null;
        ServletInputStream ins = null;

        try {
            ins = mRequest.getInputStream();
            Method m = protoBuffFactory.getDeclaredMethod("parseFrom", InputStream.class);
            result = (T) m.invoke(null, ins);
        } catch (NoSuchMethodException e) {
        } catch (SecurityException e) {
        } catch (IllegalAccessException e) {
        } catch (IllegalArgumentException e) {
        } catch (InvocationTargetException e) {
        } catch (IOException e) {
        } finally {
            if (ins != null) {
                try {
                    ins.close();
                } catch (IOException e) {
                }
            }
        }

        return result;
    }
}
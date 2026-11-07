package co.edu.escuelaing.festa.auth.web;

public record ApiError(Body error) {

    public record Body(String code, String message, Object details) {}

    public static ApiError of(String code, String message, Object details) {
        return new ApiError(new Body(code, message, details));
    }
}

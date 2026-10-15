# ---- build ----
FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src
# Project files first: the restore layer is cached until a dependency changes.
COPY Directory.Build.props ./
COPY src/Fees.Domain/Fees.Domain.csproj src/Fees.Domain/
COPY src/Fees.Application/Fees.Application.csproj src/Fees.Application/
COPY src/Fees.Infrastructure/Fees.Infrastructure.csproj src/Fees.Infrastructure/
COPY src/Fees.Api/Fees.Api.csproj src/Fees.Api/
RUN dotnet restore src/Fees.Api/Fees.Api.csproj
COPY src/ src/
RUN dotnet publish src/Fees.Api/Fees.Api.csproj -c Release -o /app --no-restore

# ---- runtime ----
FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app .
ENV ASPNETCORE_HTTP_PORTS=8088
EXPOSE 8088
# The image's own unprivileged user.
USER $APP_UID
ENTRYPOINT ["dotnet", "Fees.Api.dll"]

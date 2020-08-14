package com.chubbybuttons.blinkview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.Credentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.common.collect.ImmutableList;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.PhotosLibrarySettings;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsResponse;
import com.google.photos.library.v1.proto.NewMediaItem;
import com.google.photos.library.v1.proto.NewMediaItemResult;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import com.google.photos.library.v1.util.NewMediaItemFactory;
import com.google.photos.types.proto.Album;
import com.google.photos.types.proto.MediaItem;
import com.google.rpc.Code;
import com.google.rpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.security.GeneralSecurityException;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
@RestController
@Slf4j
@EnableScheduling
public class BlinkViewApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlinkViewApplication.class, args);
    }


    @RequestMapping(value = "/getVideos")
    @Scheduled(fixedDelay = 3600000)
    public Collection<Media> getVideos() throws Exception {
        Account account = getAccount();
        ArrayList<Media> results = new ArrayList<>();
        ArrayList<Media> processedResults = new ArrayList<>(getHistory());
        log.debug("Found {} previously processed items", processedResults.size());
        int page = 1;
        boolean hasMoreResults;
        do {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.add("token-auth", account.getAuthToken());
            HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
            String videoUrl = MessageFormat.format(videosUrl, account.getRegion(), account.getAccountId(), "1970-01-01T00:00:00+0000", page);
            ResponseEntity<Map> response = restTemplate.exchange(videoUrl, HttpMethod.GET, entity, Map.class);
            Collection<Map> mediaResults = (Collection) response.getBody().get("media");
            for (Map map : mediaResults) {
                String cameraName = map.get("device_name").toString();
                Media media = new Media();
                media.setId(map.get("id").toString());
                String mediaUrl = MessageFormat.format(postAuthUrl, account.getRegion()) + map.get("media").toString();
                media.setSourceUrl(mediaUrl);
                media.setCamera(cameraName);
                media.setCaptureTime(ZonedDateTime.parse(map.get("created_at").toString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
                if (processedResults.stream().noneMatch(o -> o.getId().equals(media.getId()))) {
                    results.add(media);
                }
            }
            int limit = Integer.parseInt(response.getBody().get("limit").toString());
            hasMoreResults = mediaResults.size() == limit;
            page++;
        } while (hasMoreResults);
        Credentials credentials = getUserCredentials(keyFileLocation, REQUIRED_SCOPES);
        PhotosLibrarySettings settings =
                PhotosLibrarySettings.newBuilder()
                        .setCredentialsProvider(
                                FixedCredentialsProvider.create(credentials))
                        .build();

        PhotosLibraryClient photosLibraryClient = PhotosLibraryClient.initialize(settings);
        for (Media result : results) {
            archiveMedia(result, photosLibraryClient);
            processedResults.add(result);
        }
        log.info("Processed {} new results", results.size());
        saveHistory(processedResults);
        photosLibraryClient.shutdown();
        photosLibraryClient.awaitTermination(60, TimeUnit.SECONDS);
        photosLibraryClient.close();
        return results;
    }

    private Collection<Media> getHistory() throws Exception {
        File historyFile = new File(getBaseArchivePath() + "/results.json");
        if (historyFile.exists()) {
            return objectMapper.readValue(historyFile, new TypeReference<List<Media>>() {
            });
        } else {
            return new ArrayList<>();
        }
    }

    private void saveHistory(Collection<Media> results) throws IOException {
        String path = getBaseArchivePath() + "/results.json";
        ObjectWriter writer = objectMapper.writer(new DefaultPrettyPrinter());
        writer.writeValue(new File(path), results);
    }

    private Media archiveMedia(Media media, PhotosLibraryClient photosLibraryClient) {
        File pic = getArchiveFile(media);
        media.setArchivePath(pic.getPath());
        try {
            if (!pic.exists()) {
                RestTemplate restMediaTemplate = new RestTemplate();
                HttpHeaders headers = new HttpHeaders();
                headers.add("token-auth", getAccount().getAuthToken());
                HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
                ResponseEntity<byte[]> mediaResponse = restMediaTemplate.exchange(media.getSourceUrl(), HttpMethod.GET, entity, byte[].class);
                FileUtils.writeByteArrayToFile(pic, mediaResponse.getBody());
                pic.setLastModified(media.getCaptureTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
                media.setImage(mediaResponse.getBody());
            } else {
                media.setImage(FileUtils.readFileToByteArray(pic));
            }
            uploadMedia(media, photosLibraryClient);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return media;
    }

    private File getArchiveFile(Media media) {
        String path = getBaseArchivePath();
        path += '/' + media.getCamera() + '/' + media.getId() + ".mp4";
        return new File(path);
    }

    private String getBaseArchivePath() {
        return getClass().getClassLoader().getResource(snapshotBaseDirectory + '/' + snapshotDirectoryName).getPath();
    }


    private void uploadMedia(Media media, PhotosLibraryClient photosLibraryClient) {
        // Open the file and automatically close it after upload
        try (RandomAccessFile file = new RandomAccessFile(getArchiveFile(media), "r")) {
            // Create a new upload request
            UploadMediaItemRequest uploadRequest =
                    UploadMediaItemRequest.newBuilder()
                            .setMimeType("video/mp4")
                            .setDataFile(file)
                            .build();
            // Upload and capture the response
            UploadMediaItemResponse uploadResponse = photosLibraryClient.uploadMediaItem(uploadRequest);
            if (uploadResponse.getError().isPresent()) {
                // If the upload results in an error, handle it
                UploadMediaItemResponse.Error error = uploadResponse.getError().get();
                throw new RuntimeException(error.getCause());
            } else {
                // If the upload is successful, get the uploadToken
                String uploadToken = uploadResponse.getUploadToken().get();
                // Use this upload token to create a media item
                NewMediaItem newMediaItem = NewMediaItemFactory
                        .createNewMediaItem(uploadToken, media.getId(), "Taken from " + media.getCamera() + " @ " + media.getCaptureTime().format(prettyFormatter));
                List<NewMediaItem> newItems = Arrays.asList(newMediaItem);

                BatchCreateMediaItemsResponse response = photosLibraryClient.batchCreateMediaItems(getAlbumId(photosLibraryClient, media), newItems);
                for (NewMediaItemResult itemsResponse : response.getNewMediaItemResultsList()) {
                    Status status = itemsResponse.getStatus();
                    if (status.getCode() == Code.OK_VALUE) {
                        // The item is successfully created in the user's library
                        MediaItem createdItem = itemsResponse.getMediaItem();
                        media.setArchiveUrl(createdItem.getProductUrl());
                        media.setArchiveId(createdItem.getId());
                    } else {
                        log.error("Unable to process: {}", itemsResponse);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error processing", e);
        }
    }


    private String getAlbumId(PhotosLibraryClient photosLibraryClient, Media media) {
        List<Album> albums = photosLibraryClient.listAlbums(true).getPage().getResponse().getAlbumsList();
        for (Album album : albums) {
            if (album.getTitle().equals(media.getCamera())) {
                return album.getId();
            }
        }
        return photosLibraryClient.createAlbum(media.getCamera()).getId();
    }


    private Credentials getUserCredentials(String credentialsPath, List<String> selectedScopes) throws IOException, GeneralSecurityException {
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(new FileInputStream(credentialsPath)));
        String clientId = clientSecrets.getDetails().getClientId();
        String clientSecret = clientSecrets.getDetails().getClientSecret();

        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        JSON_FACTORY,
                        clientSecrets,
                        selectedScopes)
                        .setDataStoreFactory(new FileDataStoreFactory(new File(authStore)))
                        .setAccessType("offline")
                        .build();
        LocalServerReceiver receiver =
                new LocalServerReceiver.Builder().setPort(LOCAL_RECEIVER_PORT).build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        return UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(credential.getRefreshToken())
                .build();
    }

    @RequestMapping(value = "/getAccount")
    public Account getAccount() {
        if (account == null) {
            account = new Account();
            RestTemplate restTemplate = new RestTemplate();
            Map<String, String> map = new HashMap<>();
            map.put("email", email);
            map.put("password", password);
            map.put("unique_id", uuid);
            try {
                String jsonRequest = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map);
                HttpEntity<String> entity = new HttpEntity<>(jsonRequest);
                ResponseEntity<Map> response = restTemplate.exchange(blinkLoginUrl, HttpMethod.POST, entity, Map.class);
                Map vals = response.getBody();
                account.setAccountId(((Map) vals.get("account")).get("id").toString());
                account.setClientId(((Map) vals.get("client")).get("id").toString());
                account.setAuthToken(((Map) vals.get("authtoken")).get("authtoken").toString());
                account.setRegion(((Map) vals.get("region")).get("tier").toString());
                account.setPinRequired(Boolean.getBoolean(((Map) vals.get("client")).get("verification_required").toString()));
            } catch (JsonProcessingException ex) {
                throw new RuntimeException(ex);
            }
        }
        return account;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    @Value("${blink.login.url}")
    private String blinkLoginUrl;
    @Value("${blink.postauth.url}")
    private String postAuthUrl;
    @Value("${blink.email}")
    private String email;
    @Value("${blink.pwd}")
    private String password;
    @Value("${blink.videos.url}")
    private String videosUrl;
    @Value("${blink.homescreen.url}")
    private String homeScreenUrl;
    @Value("${blink.uuid}")
    private String uuid;
    @Value("${snapshot.dir}")
    private String snapshotDirectoryName;
    @Value("${snapshot.base.dir}")
    private String snapshotBaseDirectory;

    @Value("${key.file.location}")
    private String keyFileLocation;

    @Value("${auth.store}")
    private String authStore;


    @Autowired
    private ObjectMapper objectMapper;

    private static final DateTimeFormatter prettyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Account account;

    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final int LOCAL_RECEIVER_PORT = 61984;
    private static final List<String> REQUIRED_SCOPES = ImmutableList.of(
            "https://www.googleapis.com/auth/photoslibrary",
            "https://www.googleapis.com/auth/photoslibrary.sharing");

}

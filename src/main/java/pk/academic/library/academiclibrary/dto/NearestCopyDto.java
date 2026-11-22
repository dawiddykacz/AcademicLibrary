package pk.academic.library.academiclibrary.dto;

public record NearestCopyDto(
        String publicationId,
        String copyId,
        String inventoryNumber,
        String branchId,
        String branchName,
        double distanceKm
) {}
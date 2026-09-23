package edu.whoi.marina.service;

import edu.whoi.marina.domain.Reservation;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExportService {

    private static final String[] HEADERS = {
            "id", "kind", "title", "vessel", "berth", "startDate", "endDate", "startTime", "endTime",
            "status", "raftingApproved", "source", "category", "notes"
    };

    public String toCsv(List<Reservation> reservations) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADERS)).append("\r\n");
        for (Reservation r : reservations) {
            sb.append(row(r)).append("\r\n");
        }
        return sb.toString();
    }

    private String row(Reservation r) {
        return String.join(",",
                csv(r.id), csv(str(r.kind)), csv(r.title), csv(r.vesselNameSnapshot), csv(r.berthNameSnapshot),
                csv(str(r.startDate)), csv(str(r.endDate)), csv(str(r.startTime)), csv(str(r.endTime)),
                csv(str(r.status)), csv(String.valueOf(r.raftingApproved)), csv(str(r.source)), csv(r.category), csv(r.notes));
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private String csv(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n");
        String escaped = value.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}
